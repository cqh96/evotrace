package io.evotrace.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Generates release notes (markdown) for a version range via the AI model,
 * falling back to a deterministic template when no AI key is configured or
 * the call fails. The result is persisted as an ai_semantic_unit with kind
 * RELEASE_NOTE so {@code GET /releases} serves it automatically.
 */
@Service
public class ReleaseNotesService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseNotesService.class);

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final AiSemanticUnitRepository semanticUnitRepository;

    public ReleaseNotesService(JdbcTemplate jdbc, ModelRouter modelRouter,
                               PromptLoader promptLoader,
                               AiSemanticUnitRepository semanticUnitRepository) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
        this.semanticUnitRepository = semanticUnitRepository;
    }

    public Map<String, Object> generate(String projectKey, String fromVersion, String toVersion) {
        Map<String, Object> range = jdbc.queryForMap("""
                SELECT t.id AS "releaseId", t.released_at AS from_at, r.released_at AS to_at
                FROM release r
                JOIN release t ON t.project_id = r.project_id
                JOIN project p ON p.id = r.project_id AND p.project_key = ?
                WHERE r.version = ? AND t.version = ?
                """, projectKey, toVersion, fromVersion);

        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT count(DISTINCT c.id) AS commits,
                       coalesce(sum(f.add_lines), 0) AS "addLines",
                       coalesce(sum(f.del_lines), 0) AS "delLines",
                       count(DISTINCT f.file_path) AS "filesChanged"
                FROM change_event c
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                LEFT JOIN change_file f ON f.event_id = c.event_id
                WHERE c.occurred_at > ? AND c.occurred_at <= ?
                """, projectKey, range.get("from_at"), range.get("to_at"));

        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT c.event_type AS type, c.commit_sha AS sha, c.author,
                       c.occurred_at AS "occurredAt",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                         LIMIT 1) AS summary
                FROM change_event c JOIN project p ON p.id = c.project_id AND p.project_key = ?
                WHERE c.occurred_at > ? AND c.occurred_at <= ?
                ORDER BY c.occurred_at
                """, projectKey, range.get("from_at"), range.get("to_at"));

        String statsText = String.format("%d commits, %d files, +%d / -%d lines",
                ((Number) stats.get("commits")).intValue(),
                ((Number) stats.get("filesChanged")).intValue(),
                ((Number) stats.get("addLines")).intValue(),
                ((Number) stats.get("delLines")).intValue());

        String changesText = buildChangesText(changes);
        String prompt = promptLoader.fill("release-note", Map.of(
                "projectKey", projectKey,
                "fromVersion", fromVersion,
                "toVersion", toVersion,
                "stats", statsText,
                "changes", changesText));

        String content = callAi(prompt, projectKey, fromVersion, toVersion, stats, changes);
        String model = modelRouter.getModelName();
        if (content == null) {
            content = fallbackTemplate(projectKey, fromVersion, toVersion, stats, changes);
            model = "template";
        }

        persist(projectKey, range.get("releaseId"), content, model);
        return Map.of("content", content, "model", model);
    }

    private String callAi(String prompt, String projectKey, String fromVersion, String toVersion,
                          Map<String, Object> stats, List<Map<String, Object>> changes) {
        if (!modelRouter.hasUsableModel()) {
            log.info("no AI key configured, using template release note for {} → {}", fromVersion, toVersion);
            return null;
        }
        try {
            ChatClient client = modelRouter.clientFor("RELEASE_NOTE");
            ReleaseNoteResult result = client.prompt().user(prompt).call().entity(ReleaseNoteResult.class);
            if (result == null || result.content() == null || result.content().isBlank()) {
                return null;
            }
            return result.content();
        } catch (Exception e) {
            log.warn("AI release note call failed, using template: {}", e.getMessage());
            return null;
        }
    }

    private String fallbackTemplate(String projectKey, String fromVersion, String toVersion,
                                    Map<String, Object> stats, List<Map<String, Object>> changes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 发布说明 ").append(fromVersion).append(" → ").append(toVersion).append("\n\n");
        sb.append("**项目**: ").append(projectKey).append("\n\n");
        sb.append("## 变更统计\n\n");
        sb.append("- 提交: ").append(((Number) stats.get("commits")).intValue()).append(" 个\n");
        sb.append("- 文件: ").append(((Number) stats.get("filesChanged")).intValue()).append(" 个\n");
        sb.append("- 代码: +").append(((Number) stats.get("addLines")).intValue())
                .append(" / -").append(((Number) stats.get("delLines")).intValue()).append(" 行\n\n");
        sb.append("## 主要变更\n\n");
        if (changes.isEmpty()) {
            sb.append("- 无记录到的事件\n");
        } else {
            for (var c : changes) {
                sb.append("- **").append(c.get("type")).append("**");
                if (c.get("summary") != null) {
                    sb.append(" ").append(c.get("summary"));
                } else if (c.get("sha") != null) {
                    sb.append(" `").append(c.get("sha")).append("`");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String buildChangesText(List<Map<String, Object>> changes) {
        StringBuilder sb = new StringBuilder();
        for (var c : changes) {
            String summary = (String) c.get("summary");
            sb.append("- ").append(c.get("type"));
            if (summary != null && !summary.isBlank()) {
                sb.append(": ").append(summary);
            }
            if (c.get("sha") != null) {
                sb.append(" (").append(c.get("sha")).append(")");
            }
            sb.append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }

    /** Replace any previous release note for this release (idempotent regeneration). */
    private void persist(String projectKey, Object releaseId, String content, String model) {
        jdbc.update("DELETE FROM ai_semantic_unit WHERE target_type = 'RELEASE' AND target_id = ? AND kind = 'RELEASE_NOTE'",
                String.valueOf(releaseId));
        AiSemanticUnit unit = new AiSemanticUnit();
        unit.setTargetType("RELEASE");
        unit.setTargetId(String.valueOf(releaseId));
        unit.setKind("RELEASE_NOTE");
        unit.setContent(content);
        unit.setModel(model);
        unit.setConfidence(BigDecimal.valueOf(1.0));
        semanticUnitRepository.save(unit);
        log.info("release note generated for {} {} → {} (model={})", projectKey, releaseId, content.length(), model);
    }

    public record ReleaseNoteResult(String content) {
    }
}
