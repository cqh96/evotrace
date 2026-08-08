package io.evotrace.server.requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原型（线框）存取：每需求一行 pages JSONB；服务端做结构校验与上限裁剪
 * （≤10 页、每页 ≤50 元素），杜绝脏 JSON 落库；AI 生成原型复用编辑器 JSON 模型，
 * 生成结果由用户在编辑器内微调。
 */
@Service
public class RequirementPrototypeService {

    private static final Logger log = LoggerFactory.getLogger(RequirementPrototypeService.class);
    private static final int MAX_PAGES = 10;
    private static final int MAX_ELEMENTS_PER_PAGE = 50;
    // 与前端 PrototypeEditor 组件清单一致
    private static final java.util.Set<String> ELEMENT_TYPES = java.util.Set.of(
            "BUTTON", "INPUT", "TEXT", "TABLE", "NAV", "IMAGE", "LIST", "SELECTOR", "CONTAINER");

    // 代码库惯例：Jackson 2 ObjectMapper 无 Spring bean（Spring Boot 4 自带 Jackson 3）
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>> PAGES_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final PmAiGateway aiGateway;

    public RequirementPrototypeService(JdbcTemplate jdbc, PmAiGateway aiGateway) {
        this.jdbc = jdbc;
        this.aiGateway = aiGateway;
    }

    /** 读取原型（无记录返回空 pages）。 */
    public Map<String, Object> get(Long requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pages, updated_by AS "updatedBy", updated_at AS "updatedAt"
                FROM requirement_prototype WHERE requirement_id = ?
                """, requirementId);
        if (rows.isEmpty()) {
            return Map.of("pages", List.of(), "updatedBy", null, "updatedAt", null);
        }
        Map<String, Object> row = rows.get(0);
        // JSONB 列由 JDBC 返回 PGobject（toString 即 JSON 值字符串），解析为结构供前端直接消费
        Object pagesObj = row.get("pages");
        if (pagesObj != null) {
            try {
                row.put("pages", mapper.readValue(String.valueOf(pagesObj), PAGES_TYPE));
            } catch (Exception e) {
                log.warn("prototype pages parse failed", e);
                row.put("pages", List.of());
            }
        }
        return row;
    }

    /** 保存原型（结构校验 + 裁剪后 upsert）。 */
    public Map<String, Object> save(Long requirementId, String pagesJson, String user) {
        if (pagesJson == null || pagesJson.isBlank()) {
            throw new IllegalArgumentException("原型数据不能为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(pagesJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("原型 JSON 解析失败: " + e.getMessage());
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("原型 pages 必须是数组");
        }
        String trimmed = trimPages(root);
        jdbc.update("""
                INSERT INTO requirement_prototype(requirement_id, pages, updated_by, updated_at)
                VALUES (?, ?::jsonb, ?, now())
                ON CONFLICT (requirement_id) DO UPDATE
                SET pages = EXCLUDED.pages, updated_by = EXCLUDED.updated_by, updated_at = now()
                """, requirementId, trimmed, user);
        return Map.of("success", true, "pagesSaved", true);
    }

    /** AI 生成原型（返回 pages JSON 数组字符串与生成信息，由编辑器渲染可微调）。 */
    public Map<String, Object> aiGenerate(Long requirementId, String title, String prompt) {
        PmAiGateway.PrototypeAiResult result = aiGateway.generate(PmAiGateway.TASK_PROTO, "prototype-generate",
                Map.of("title", title == null ? "" : title, "prompt", prompt == null ? "" : prompt),
                PmAiGateway.PrototypeAiResult.class);
        if (result == null || result.pages() == null || result.pages().isEmpty()) {
            return Map.of("pages", List.of(), "model", "template", "generated", false,
                    "message", "未配置可用 AI 模型（apiKey 缺失或为占位符），请在「AI 模型配置」启用后重试");
        }
        // 转回 JSON 数组（供前端直接渲染），并做与 save 相同的裁剪
        try {
            String json = mapper.writeValueAsString(result.pages());
            String trimmed = trimPages(mapper.readTree(json));
            List<Map<String, Object>> pages = mapper.readValue(trimmed,
                    mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            return Map.of("pages", pages, "model", aiGateway.modelName(), "generated", true, "message", "");
        } catch (Exception e) {
            log.warn("pm-ai: prototype result mapping failed", e);
            return Map.of("pages", List.of(), "model", aiGateway.modelName(), "generated", false,
                    "message", "AI 生成结果解析失败，请重试");
        }
    }

    /** 裁剪并校验：页数/元素数上限、type 枚举、坐标与尺寸数值化。 */
    private String trimPages(JsonNode root) {
        List<Map<String, Object>> pages = new ArrayList<>();
        int pageIdx = 0;
        for (JsonNode pageNode : root) {
            if (pageIdx >= MAX_PAGES) {
                break;
            }
            pageIdx++;
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("id", textOr(pageNode, "id", "p_" + pageIdx));
            page.put("name", textOr(pageNode, "name", "页面 " + pageIdx));
            page.put("width", intOr(pageNode, "width", 375));
            page.put("height", intOr(pageNode, "height", 812));
            List<Map<String, Object>> elements = new ArrayList<>();
            JsonNode elementsNode = pageNode.get("elements");
            if (elementsNode != null && elementsNode.isArray()) {
                int elemIdx = 0;
                for (JsonNode el : elementsNode) {
                    if (elemIdx >= MAX_ELEMENTS_PER_PAGE) {
                        break;
                    }
                    String type = textOr(el, "type", "");
                    if (!ELEMENT_TYPES.contains(type)) {
                        continue; // 未知类型丢弃，不拒绝整单
                    }
                    elemIdx++;
                    Map<String, Object> element = new LinkedHashMap<>();
                    element.put("id", textOr(el, "id", "el_" + pageIdx + "_" + elemIdx));
                    element.put("type", type);
                    element.put("x", intOr(el, "x", 0));
                    element.put("y", intOr(el, "y", 0));
                    element.put("w", intOr(el, "w", 120));
                    element.put("h", intOr(el, "h", 40));
                    JsonNode props = el.get("props");
                    element.put("props", props != null && props.isObject() ? mapper.convertValue(props, Map.class) : Map.of());
                    element.put("linkTo", el.has("linkTo") && !el.get("linkTo").isNull() ? el.get("linkTo").asText() : "");
                    elements.add(element);
                }
            }
            page.put("elements", elements);
            pages.add(page);
        }
        try {
            return mapper.writeValueAsString(pages);
        } catch (Exception e) {
            throw new IllegalArgumentException("原型序列化失败");
        }
    }

    private static String textOr(JsonNode node, String field, String dflt) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : dflt;
    }

    private static int intOr(JsonNode node, String field, int dflt) {
        JsonNode v = node.get(field);
        if (v != null && v.isNumber()) {
            int n = v.asInt(dflt);
            return Math.max(0, n);
        }
        return dflt;
    }
}
