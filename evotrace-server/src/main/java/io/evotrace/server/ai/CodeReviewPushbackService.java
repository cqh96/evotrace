package io.evotrace.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审查结果回写 Git 平台（借鉴 PR-Agent 的评论注入）。
 * <p>将已落库的 review_finding 整理成结构化评论文本，通过 GitLab Merge Request notes API
 * 写为 MR 评论。需要 evotrace.gitlab.token 与 project.repo_url（用于推断命名空间/项目），
 * 由调用方传入 MR iid。</p>
 */
@Service
public class CodeReviewPushbackService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewPushbackService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final HttpClient httpClient;

    /** 从 repo_url 提取 namespace/project，如 git@host:group/sub/proj.git 或 https://host/group/sub/proj.git */
    private static final Pattern REPO_PATH = Pattern.compile(
            "(?:^[^@/]+@[^:]+:|^https?://[^/]+/)(.*?)(?:\\.git)?$");

    @Value("${evotrace.gitlab.token:}")
    private String token;

    @Value("${evotrace.gitlab.base-url:}")
    private String baseUrlOverride;

    public CodeReviewPushbackService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 将某 review 的 findings 回写为 GitLab MR 评论。
     *
     * @param projectKey 项目 key（用于查 repo_url 与 project_id）
     * @param eventId    变更事件 id（定位 ai_code_review）
     * @param mergeRequestIid MR iid（如 12）
     * @return 回写结果（含评论 URL）
     */
    public Map<String, Object> pushBack(String projectKey, String eventId, Integer mergeRequestIid) {
        if (token == null || token.isBlank()) {
            return Map.of("error", "未配置 evotrace.gitlab.token，无法回写");
        }
        if (mergeRequestIid == null) {
            return Map.of("error", "缺少 mergeRequestIid（MR 编号）");
        }

        Map<String, Object> review = jdbc.queryForMap(
                "SELECT * FROM ai_code_review WHERE change_event_id = ?", eventId);
        Long reviewId = ((Number) review.get("id")).longValue();
        boolean already = Boolean.TRUE.equals(review.get("pushed_back"));
        if (already) {
            return Map.of("alreadyPushedBack", true, "url", review.get("push_back_url"));
        }

        List<Map<String, Object>> findings = jdbc.queryForList("""
                SELECT severity, category, file_path, line_range, title, description, suggestion
                FROM review_finding WHERE review_id = ? ORDER BY id
                """, reviewId);

        String body = buildCommentBody(review, findings);

        String repoUrl = jdbc.queryForObject(
                "SELECT repo_url FROM project WHERE project_key = ?", String.class, projectKey);
        String projectPath = resolveProjectPath(repoUrl);
        if (projectPath == null) {
            return Map.of("error", "无法从 repo_url 推断 GitLab 项目路径: " + repoUrl);
        }
        String base = resolveBaseUrl(repoUrl);

        try {
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String url = base + "/api/v4/projects/" + encodedPath
                    + "/merge_requests/" + mergeRequestIid + "/notes";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(Map.of("body", body))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Map.of("error", "GitLab API " + response.statusCode() + ": "
                        + (response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length()))));
            }
            String discussionUrl = url + "/" + extractNoteId(response.body());
            jdbc.update("""
                    UPDATE ai_code_review SET pushed_back = true, push_back_url = ?, pushed_back_at = now()
                    WHERE id = ?
                    """, discussionUrl, reviewId);
            return Map.of("pushedBack", true, "eventId", eventId, "findings", findings.size(), "url", discussionUrl);
        } catch (Exception e) {
            log.warn("failed to push back review {} to GitLab: {}", eventId, e.getMessage());
            return Map.of("error", "回写失败: " + e.getMessage());
        }
    }

    private String buildCommentBody(Map<String, Object> review, List<Map<String, Object>> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 **EvoTrace AI 审查报告**\n\n");
        sb.append("- 综合评分：**").append(review.get("overall_score")).append("/100** · 结论：")
          .append(review.get("overall_verdict")).append("\n");
        if (review.get("diff_summary") != null) {
            sb.append("- 变更摘要：").append(review.get("diff_summary")).append("\n");
        }
        if (findings.isEmpty()) {
            sb.append("\n未发现明显问题。");
            return sb.toString();
        }
        sb.append("\n**发现项（").append(findings.size()).append("）**\n");
        for (Map<String, Object> f : findings) {
            sb.append("\n---\n");
            sb.append("**[").append(f.get("severity")).append(" · ").append(f.get("category")).append("]** ")
              .append(f.get("title")).append("\n");
            if (f.get("file_path") != null) {
                sb.append("`").append(f.get("file_path"));
                if (f.get("line_range") != null) {
                    sb.append(":").append(f.get("line_range"));
                }
                sb.append("`\n");
            }
            if (f.get("description") != null) {
                sb.append(f.get("description")).append("\n");
            }
            if (f.get("suggestion") != null) {
                sb.append("> 建议：").append(f.get("suggestion")).append("\n");
            }
        }
        return sb.toString();
    }

    private String extractNoteId(String body) {
        try {
            var node = mapper.readTree(body);
            return node.has("id") ? String.valueOf(node.get("id").asLong()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 repo_url 提取 GitLab 项目路径（namespace/subgroup/project）。 */
    private String resolveProjectPath(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        Matcher m = REPO_PATH.matcher(repoUrl.trim());
        if (!m.matches()) {
            return null;
        }
        String path = m.group(1);
        return path == null || path.isBlank() ? null : path;
    }

    private String resolveBaseUrl(String repoUrl) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return trimTrailingSlash(baseUrlOverride.trim());
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(repoUrl);
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}