package io.evotrace.server.testing;

import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.ai.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 缺陷根因分析（对标 MeterSphere AI 赋能）：结合缺陷描述、关联变更摘要与相关用例，
 * 生成缺陷根因分析与修复建议。模型不可用时回退到确定性模板。
 */
@Service
public class AiBugAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AiBugAnalyzer.class);

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public AiBugAnalyzer(JdbcTemplate jdbc, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    public Map<String, Object> analyze(Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap("""
                SELECT b.id, b.title, b.description, b.severity, b.status, b.found_version AS "foundVersion",
                       b.fixed_version AS "fixedVersion"
                FROM bug_ticket b WHERE b.id = ?
                """, bugId);
        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT bl.link_type AS "linkType", c.commit_sha AS "commitSha", c.author,
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                         LIMIT 1) AS summary
                FROM bug_change_link bl JOIN change_event c ON c.event_id = bl.change_event_id
                WHERE bl.bug_id = ? ORDER BY c.occurred_at
                """, bugId);
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT DISTINCT f.file_path AS "filePath", f.change_kind AS "changeKind"
                FROM bug_change_link bl JOIN change_file f ON f.event_id = bl.change_event_id
                WHERE bl.bug_id = ?
                """, bugId);

        String changesText = changes.isEmpty() ? "(none)"
                : String.join("\n", changes.stream().map(c -> "- [" + c.get("linkType") + "] "
                        + (c.get("summary") != null ? c.get("summary") : c.get("commitSha"))).toList());
        String filesText = files.isEmpty() ? "(none)" : String.join("\n", files.stream().map(f -> "- " + f.get("filePath")).toList());

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("bugId", bugId);
        out.put("title", bug.get("title"));
        out.put("analysis", generateText(bug, changesText, filesText));
        out.put("aiGenerated", modelRouter.hasUsableModel());
        out.put("model", modelRouter.hasUsableModel() ? modelRouter.getModelName() : null);
        return out;
    }

    private String generateText(Map<String, Object> bug, String changesText, String filesText) {
        String prompt = promptLoader.fill("bug-analysis", Map.of(
                "title", String.valueOf(bug.get("title")),
                "description", bug.get("description") != null ? bug.get("description").toString() : "",
                "severity", String.valueOf(bug.get("severity")),
                "foundVersion", String.valueOf(bug.getOrDefault("foundVersion", "")),
                "changes", changesText,
                "files", filesText));
        if (!modelRouter.hasUsableModel()) {
            return fallback(bug, changesText, filesText);
        }
        try {
            ChatClient client = modelRouter.clientFor("BUG_ANALYSIS");
            BugAnalysisResult r = client.prompt().user(prompt).call().entity(BugAnalysisResult.class);
            if (r != null && r.analysis() != null && !r.analysis().isBlank()) return r.analysis();
            return fallback(bug, changesText, filesText);
        } catch (Exception e) {
            log.warn("AI bug analysis failed, using template: {}", e.getMessage());
            return fallback(bug, changesText, filesText);
        }
    }

    private String fallback(Map<String, Object> bug, String changesText, String filesText) {
        StringBuilder sb = new StringBuilder();
        sb.append("**缺陷**：").append(bug.get("title")).append("（").append(bug.get("severity")).append("）\n");
        sb.append("**可能根因**：结合缺陷描述与关联变更，建议检查以下变更是否引入回归：\n").append(changesText).append("\n");
        sb.append("**涉及文件**：\n").append(filesText).append("\n");
        sb.append("**建议**：补充覆盖上述变更的回归用例，并验证修复方案。");
        return sb.toString();
    }

    public record BugAnalysisResult(String analysis) {}
}