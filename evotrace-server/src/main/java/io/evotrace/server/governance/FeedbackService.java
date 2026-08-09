package io.evotrace.server.governance;

import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.ai.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈 → 需求/缺陷（P2）。对外部反馈做 AI 语义分析，自动分类并转成需求或缺陷，
 * 复用 ModelRouter（失败时降级到关键词启发式）。
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public FeedbackService(JdbcTemplate jdbc, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    public List<Map<String, Object>> list(Long projectId, String status) {
        String filter = status != null && !status.isBlank()
                ? " AND status = '" + status.replaceAll("[^A-Z_]", "") + "'" : "";
        return jdbc.queryForList("""
                SELECT f.id, f.source, f.content, f.status,
                       f.ai_analysis AS "aiAnalysis", f.ai_model AS "aiModel",
                       f.converted_requirement_id AS "convertedRequirementId",
                       f.converted_bug_id AS "convertedBugId",
                       f.created_at AS "createdAt",
                       r.title AS "requirementTitle", b.title AS "bugTitle"
                FROM feedback f
                LEFT JOIN requirement r ON r.id = f.converted_requirement_id
                LEFT JOIN bug_ticket b ON b.id = f.converted_bug_id
                WHERE f.project_id = ?
                """ + filter + " ORDER BY f.created_at DESC", projectId);
    }

    /** 记录一条反馈。 */
    @Transactional
    public Map<String, Object> create(Long projectId, String content, String source, String createdBy) {
        Long id = jdbc.queryForObject("""
                INSERT INTO feedback(project_id, content, source, created_by)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, projectId, content,
                source != null ? source : "MANUAL", createdBy);
        return Map.of("success", true, "id", id);
    }

    /** AI 分析反馈：识别是需求还是缺陷，并给出标题/分类/优先级建议。 */
    public Map<String, Object> analyze(Long projectId, Long feedbackId) {
        Map<String, Object> fb = jdbc.queryForMap(
                "SELECT * FROM feedback WHERE id = ? AND project_id = ?", feedbackId, projectId);
        String content = fb.get("content") != null ? fb.get("content").toString() : "";

        AnalysisResult result = null;
        if (modelRouter.hasUsableModel()) {
            try {
                ChatClient client = modelRouter.clientFor("FEEDBACK_ANALYSIS");
                String prompt = promptLoader.fill("feedback-analysis", Map.of("content", content));
                result = client.prompt().user(prompt).call().entity(AnalysisResult.class);
            } catch (Exception e) {
                log.warn("AI feedback analysis failed, using heuristic: {}", e.getMessage());
            }
        }
        if (result == null || result.type() == null) {
            result = heuristic(content);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedbackId", feedbackId);
        out.put("type", result.type());
        out.put("title", result.title());
        out.put("priority", result.priority());
        out.put("summary", result.summary());
        out.put("aiGenerated", modelRouter.hasUsableModel());
        out.put("model", modelRouter.hasUsableModel() ? modelRouter.getModelName() : null);

        // 保存分析结果
        jdbc.update("UPDATE feedback SET ai_analysis = ?, ai_model = ? WHERE id = ?",
                Map.of("type", result.type(), "title", result.title(),
                        "priority", result.priority(), "summary", result.summary()).toString(),
                modelRouter.hasUsableModel() ? modelRouter.getModelName() : null, feedbackId);
        return out;
    }

    /** 将反馈转换为需求或缺陷。 */
    @Transactional
    public Map<String, Object> convert(Long projectId, Long feedbackId, String type, String title,
                                       String priority, String summary) {
        Map<String, Object> fb = jdbc.queryForMap(
                "SELECT * FROM feedback WHERE id = ? AND project_id = ?", feedbackId, projectId);
        String content = fb.get("content") != null ? fb.get("content").toString() : "";
        String t = title != null && !title.isBlank() ? title : content.substring(0, Math.min(60, content.length()));
        String prio = priority != null && !priority.isBlank() ? priority : "P2";
        String desc = summary != null && !summary.isBlank() ? summary : content;

        Long targetId;
        if ("BUG".equalsIgnoreCase(type)) {
            targetId = jdbc.queryForObject("""
                    INSERT INTO bug_ticket(project_id, title, description, severity, status, source)
                    VALUES (?, ?, ?, ?, 'OPEN', 'FEEDBACK') RETURNING id
                    """, Long.class, projectId, t, desc, prio);
            jdbc.update("UPDATE feedback SET status='CONVERTED', converted_bug_id=? WHERE id=?",
                    targetId, feedbackId);
        } else {
            targetId = jdbc.queryForObject("""
                    INSERT INTO requirement(project_id, workspace_id, title, description, priority,
                        status, source)
                    VALUES (?, (SELECT workspace_id FROM project WHERE id=?), ?, ?, ?, 'DRAFT', 'FEEDBACK')
                    RETURNING id
                    """, Long.class, projectId, projectId, t, desc, prio);
            jdbc.update("UPDATE feedback SET status='CONVERTED', converted_requirement_id=? WHERE id=?",
                    targetId, feedbackId);
        }
        return Map.of("success", true, "type", type, "targetId", targetId);
    }

    @Transactional
    public void ignore(Long projectId, Long feedbackId) {
        jdbc.update("UPDATE feedback SET status='IGNORED' WHERE id=? AND project_id=?", feedbackId, projectId);
    }

    private AnalysisResult heuristic(String content) {
        String lower = content.toLowerCase();
        String type = (lower.contains("bug") || lower.contains("异常") || lower.contains("报错")
                || lower.contains("崩溃") || lower.contains("失败") || lower.contains("缺陷")
                || lower.contains("闪退")) ? "BUG" : "REQUIREMENT";
        String title = content.substring(0, Math.min(40, content.length()));
        return new AnalysisResult(type, title, "P2", content);
    }

    public record AnalysisResult(String type, String title, String priority, String summary) {
    }
}