package io.evotrace.server.requirement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求文档（PRD）版本链管理：每次保存生成新版本（版本不可变），
 * 支持查看历史版本与回滚（复制旧版另存新版本）；AI 生成 PRD 初稿不落库，
 * 由用户确认保存后才成版本。
 */
@Service
public class RequirementDocumentService {

    private static final int MAX_CONTENT_CHARS = 200_000;

    private final JdbcTemplate jdbc;
    private final PmAiGateway aiGateway;

    public RequirementDocumentService(JdbcTemplate jdbc, PmAiGateway aiGateway) {
        this.jdbc = jdbc;
        this.aiGateway = aiGateway;
    }

    /** 最新版本文档；无文档时返回 version=0 空结构。 */
    public Map<String, Object> latest(Long requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT version, title, content, created_by AS "createdBy", created_at AS "createdAt"
                FROM requirement_document WHERE requirement_id = ? ORDER BY version DESC LIMIT 1
                """, requirementId);
        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("version", 0);
            empty.put("title", "");
            empty.put("content", "");
            empty.put("createdBy", null);
            empty.put("createdAt", null);
            return empty;
        }
        return rows.get(0);
    }

    /** 版本列表（不含 content）。 */
    public List<Map<String, Object>> versions(Long requirementId) {
        return jdbc.queryForList("""
                SELECT version, title, created_by AS "createdBy", created_at AS "createdAt"
                FROM requirement_document WHERE requirement_id = ? ORDER BY version DESC
                """, requirementId);
    }

    /** 指定版本全文。 */
    public Map<String, Object> version(Long requirementId, int version) {
        try {
            return jdbc.queryForMap("""
                    SELECT version, title, content, created_by AS "createdBy", created_at AS "createdAt"
                    FROM requirement_document WHERE requirement_id = ? AND version = ?
                    """, requirementId, version);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("文档版本不存在: v" + version);
        }
    }

    /** 保存为新版本（version = max+1），返回新版本信息。 */
    @Transactional
    public Map<String, Object> save(Long requirementId, String title, String content, String author) {
        String cleanTitle = title == null || title.isBlank() ? "PRD" : title;
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("文档内容超过上限(200KB)");
        }
        Integer maxVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM requirement_document WHERE requirement_id = ?",
                Integer.class, requirementId);
        int version = maxVersion + 1;
        jdbc.update("""
                INSERT INTO requirement_document(requirement_id, version, title, content, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, requirementId, version, cleanTitle, content, author);
        return Map.of("version", version, "title", cleanTitle, "content", content, "author", author);
    }

    /** 回滚：读取指定版本内容另存为新版本（历史不可变，可追溯）。 */
    @Transactional
    public Map<String, Object> rollback(Long requirementId, int version, String author) {
        Map<String, Object> old = version(requirementId, version);
        return save(requirementId, (String) old.get("title"), (String) old.get("content"), author);
    }

    /** AI 生成 PRD 初稿（不落库）；无可用模型时返回确定性骨架。 */
    public Map<String, Object> aiDraft(Long requirementId, Map<String, Object> reqRow, String userPrompt) {
        String title = str(reqRow, "title");
        String description = userPrompt != null && !userPrompt.isBlank()
                ? userPrompt : str(reqRow, "description");
        String userStory = str(reqRow, "user_story");
        String businessValue = str(reqRow, "business_value");

        PmAiGateway.PrdDraftResult result = aiGateway.generate(PmAiGateway.TASK_PRD, "prd-draft", Map.of(
                "title", title, "description", description,
                "userStory", userStory, "businessValue", businessValue), PmAiGateway.PrdDraftResult.class);

        if (result == null || result.content() == null || result.content().isBlank()) {
            return Map.of("content", prdSkeleton(title, description),
                    "model", "template", "generated", false,
                    "message", "未配置可用 AI 模型（apiKey 缺失或为占位符），已生成 PRD 骨架，请在「AI 模型配置」启用后重试");
        }
        return Map.of("content", result.content(), "model", aiGateway.modelName(), "generated", true, "message", "");
    }

    /** 无 AI 时的确定性 PRD 骨架（章节头，用户可在此基础上填写）。 */
    private String prdSkeleton(String title, String description) {
        return "# " + (title == null || title.isBlank() ? "PRD" : title) + "\n\n"
                + "## 背景与问题\n\n" + (description == null ? "" : description) + "\n\n"
                + "## 目标\n\n- \n\n## 范围\n\n- 包含：\n- 不包含：\n\n"
                + "## 用户故事\n\n- \n\n## 验收标准\n\n- [ ] \n\n"
                + "## 里程碑\n\n- \n";
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v.toString() : "";
    }
}
