package io.evotrace.server.gitlab;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventSource;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.ingestion.BlobStoreService;
import io.evotrace.server.ingestion.IngestionService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GitLab 仓库集成服务（V2.5）。
 * <p>
 * 从"被动收 Webhook"升级为"主动仓库级导入"：clone 自建仓库、按 commit 拓扑序
 * 喂入既有事件管道（复用 {@link IngestionService#acceptWebhook}），支撑历史回填与增量同步。
 */
@Service
public class GitLabService {

    /** Git 规范空树 ObjectId（对所有仓库恒定有效）。 */
    private static final String EMPTY_TREE_ID = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    private static final Logger log = LoggerFactory.getLogger(GitLabService.class);

    private final JdbcTemplate jdbc;
    private final IngestionService ingestionService;
    private final BlobStoreService blobStore;
    private final File repoBaseDir;

    public GitLabService(JdbcTemplate jdbc, IngestionService ingestionService, BlobStoreService blobStore) {
        this.jdbc = jdbc;
        this.ingestionService = ingestionService;
        this.blobStore = blobStore;
        String base = System.getProperty("evotrace.gitlab.repoBase", "./data/gitlab-repos");
        this.repoBaseDir = new File(base);
        this.repoBaseDir.mkdirs();
    }

    /** 配置 GitLab 连接（按项目）。 */
    @Transactional
    public void connect(Long projectId, String baseUrl, String authType, String token, String namespace) {
        jdbc.update("""
                    INSERT INTO gitlab_connection (project_id, base_url, auth_type, token_enc, default_namespace)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (project_id, base_url) DO UPDATE SET
                        auth_type = EXCLUDED.auth_type, token_enc = EXCLUDED.token_enc,
                        default_namespace = EXCLUDED.default_namespace, updated_at = now()
                    """, projectId, baseUrl, authType, token, namespace);
    }

    /** 当前项目的 GitLab 连接配置（不含明文令牌）。 */
    public Map<String, Object> getConnect(Long projectId) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT base_url, auth_type, default_namespace, token_enc FROM gitlab_connection WHERE project_id = ?",
                    projectId);
            String token = (String) row.get("token_enc");
            String masked = (token == null || token.isEmpty())
                    ? ""
                    : (token.length() > 8 ? token.substring(0, 8) + "••••" : "••••");
            return Map.of(
                    "configured", true,
                    "baseUrl", row.get("base_url"),
                    "authType", row.get("auth_type"),
                    "namespace", row.get("default_namespace") == null ? "" : row.get("default_namespace"),
                    "tokenMasked", masked);
        } catch (EmptyResultDataAccessException e) {
            return Map.of("configured", false);
        }
    }

    /** 清除项目的 GitLab 连接配置。 */
    @Transactional
    public void disconnect(Long projectId) {
        jdbc.update("DELETE FROM gitlab_connection WHERE project_id = ?", projectId);
    }

    /** 导入仓库：clone 到本地并喂入历史 commit。 */
    @Transactional
    public Map<String, Object> importRepo(Long projectId, String repoPath, String defaultBranch) {
        String baseUrl = baseUrl(projectId);
        String token = token(projectId);
        if (baseUrl == null || token == null) {
            throw new IllegalArgumentException("项目尚未配置 GitLab 连接");
        }
        String repoUrl = baseUrl.endsWith("/") ? baseUrl + repoPath + ".git" : baseUrl + "/" + repoPath + ".git";
        File local = new File(repoBaseDir, projectId + "__" + repoPath.replace('/', '_'));

        Long repoId = jdbc.queryForObject("""
                INSERT INTO repo_import (project_id, repo_path, default_branch, clone_status, local_path)
                VALUES (?, ?, ?, 'CLONING', ?)
                ON CONFLICT (project_id, repo_path) DO UPDATE SET clone_status = 'CLONING', updated_at = now()
                RETURNING id
                """, Long.class, projectId, repoPath, defaultBranch, local.getAbsolutePath());

        try {
            CredentialsProvider cp = new UsernamePasswordCredentialsProvider(
                    "oauth2", token);
            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(local)
                    .setBranch(defaultBranch)
                    .setCredentialsProvider(cp)
                    .call()) {
                int count = ingestCommits(git, projectId, repoPath, defaultBranch);
                jdbc.update("""
                        INSERT INTO repo_import_log (repo_id, sync_type, status, commits_count, message, finished_at)
                        VALUES (?, 'FULL', 'SUCCESS', ?, 'clone+ingest', now())
                        """, repoId, count);
                jdbc.update("""
                        UPDATE repo_import SET clone_status='SYNCED', last_synced_sha=?,
                                               last_error=NULL, updated_at=now()
                        WHERE id=?
                        """, headSha(git), repoId);
                return Map.of("repoId", repoId, "status", "SYNCED", "commits", count);
            }
        } catch (Exception e) {
            log.warn("GitLab import failed repo={}: {}", repoPath, e.getMessage());
            jdbc.update("""
                    UPDATE repo_import SET clone_status='FAILED', last_error=?, updated_at=now() WHERE id=?
                    """, e.getMessage(), repoId);
            throw new RuntimeException("仓库导入失败: " + e.getMessage(), e);
        }
    }

    /** 增量同步：fetch 后喂入新增 commit。 */
    @Transactional
    public Map<String, Object> sync(Long projectId, Long repoId) {
        Map<String, Object> repo = jdbc.queryForMap(
                "SELECT * FROM repo_import WHERE id = ? AND project_id = ?", repoId, projectId);
        File local = new File((String) repo.get("local_path"));
        if (!local.exists()) {
            throw new IllegalArgumentException("仓库尚未克隆，请先导入");
        }
        String token = token(projectId);
        String repoPath = (String) repo.get("repo_path");
        String defaultBranch = (String) repo.get("default_branch");
        CredentialsProvider cp = new UsernamePasswordCredentialsProvider("oauth2", token);
        try (Repository repository = new FileRepository(new File(local, ".git"));
             Git git = new Git(repository)) {
            git.fetch().setCredentialsProvider(cp).call();
            int count = ingestCommits(git, projectId, repoPath, defaultBranch);
            jdbc.update("""
                    INSERT INTO repo_import_log (repo_id, sync_type, status, commits_count, message, finished_at)
                    VALUES (?, 'INCREMENTAL', 'SUCCESS', ?, 'fetch+ingest', now())
                    """, repoId, count);
            jdbc.update("UPDATE repo_import SET last_synced_sha=?, updated_at=now() WHERE id=?",
                    headSha(git), repoId);
            return Map.of("repoId", repoId, "status", "SYNCED", "newCommits", count);
        } catch (Exception e) {
            log.warn("GitLab sync failed repoId={}: {}", repoId, e.getMessage());
            throw new RuntimeException("增量同步失败: " + e.getMessage(), e);
        }
    }

    /** 仓库列表与同步状态。 */
    public List<Map<String, Object>> listRepos(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, repo_path, default_branch, clone_status, last_synced_sha,
                       schedule_cron, last_error, updated_at
                FROM repo_import WHERE project_id = ? ORDER BY created_at DESC""", projectId);
    }

    /** 同步日志。 */
    public List<Map<String, Object>> logs(Long repoId) {
        return jdbc.queryForList("""
                SELECT sync_type, status, commits_count, message, started_at, finished_at
                FROM repo_import_log WHERE repo_id = ? ORDER BY started_at DESC LIMIT 50""", repoId);
    }

    // ---------- private helpers ----------

    private int ingestCommits(Git git, Long projectId, String repoPath, String defaultBranch) throws GitAPIException {
        String projectKey = jdbc.queryForObject(
                "SELECT project_key FROM project WHERE id = ?", String.class, projectId);
        Iterable<RevCommit> commits = git.log().call();
        int count = 0;
        for (RevCommit c : commits) {
            long when = c.getCommitTime() * 1000L;
            String commitSha = c.getName();
            Envelope envelope = new Envelope(
                    Envelope.CURRENT_VERSION,
                    UUID.randomUUID().toString(),
                    projectKey,
                    "gitlab:" + repoPath,
                    EventType.CODE_COMMIT,
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(when), ZoneOffset.UTC),
                    EventSource.GITLAB_REPO,
                    "gitlab:" + repoPath + ":" + commitSha,
                    Map.of(
                            "branch", defaultBranch,
                            "commitSha", commitSha,
                            "message", c.getShortMessage(),
                            "authorName", c.getAuthorIdent() != null ? c.getAuthorIdent().getName() : "unknown",
                            "files", fileChangesForCommit(git, c)
                    ),
                    null);
            ingestionService.acceptWebhook(envelope, null);
            count++;
        }
        return count;
    }

    /** 计算单个 commit 相对其父提交的文件变更（含真实 diff 与行数）。 */
    private List<Map<String, Object>> fileChangesForCommit(Git git, RevCommit c) {
        List<Map<String, Object>> files = new ArrayList<>();
        try (ObjectReader reader = git.getRepository().newObjectReader()) {
            CanonicalTreeParser oldTree = new CanonicalTreeParser();
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            if (c.getParentCount() > 0) {
                oldTree.reset(reader, c.getParent(0).getTree().getId());
            } else {
                // 根提交：对比 git 规范空树
                oldTree.reset(reader, ObjectId.fromString(EMPTY_TREE_ID));
            }
            newTree.reset(reader, c.getTree().getId());
            List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            for (DiffEntry e : entries) {
                DiffEntry.ChangeType ct = e.getChangeType();
                CodeCommitPayload.FileChange.ChangeKind kind = switch (ct) {
                    case ADD -> CodeCommitPayload.FileChange.ChangeKind.ADDED;
                    case DELETE -> CodeCommitPayload.FileChange.ChangeKind.DELETED;
                    case RENAME -> CodeCommitPayload.FileChange.ChangeKind.RENAMED;
                    default -> CodeCommitPayload.FileChange.ChangeKind.MODIFIED;
                };
                String diffText = diffTextFor(git, e);
                int[] lines = countDiffLines(diffText);
                String blobRef = (diffText == null || diffText.isBlank()) ? null : blobStore.put(diffText);
                Map<String, Object> m = new HashMap<>();
                m.put("oldPath", (ct == DiffEntry.ChangeType.ADD) ? null : e.getOldPath());
                m.put("newPath", (ct == DiffEntry.ChangeType.DELETE) ? null : e.getNewPath());
                m.put("kind", kind.name());
                m.put("addLines", lines[0]);
                m.put("delLines", lines[1]);
                m.put("diffBlobRef", blobRef);
                files.add(m);
            }
        } catch (Exception ex) {
            log.warn("failed to compute diff for commit {}: {}", c.getName(), ex.getMessage());
        }
        return files;
    }

    private String diffTextFor(Git git, DiffEntry e) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(git.getRepository());
            formatter.format(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** 从 unified diff 文本统计新增/删除行数。 */
    static int[] countDiffLines(String diff) {
        int add = 0;
        int del = 0;
        if (diff == null || diff.isEmpty()) {
            return new int[]{0, 0};
        }
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                add++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                del++;
            }
        }
        return new int[]{add, del};
    }

    private String headSha(Git git) {
        try {
            return git.getRepository().resolve("HEAD").name();
        } catch (Exception e) {
            return null;
        }
    }

    private String baseUrl(Long projectId) {
        return jdbc.queryForObject(
                "SELECT base_url FROM gitlab_connection WHERE project_id = ?", String.class, projectId);
    }

    private String token(Long projectId) {
        return jdbc.queryForObject(
                "SELECT token_enc FROM gitlab_connection WHERE project_id = ?", String.class, projectId);
    }
}