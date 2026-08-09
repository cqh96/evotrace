package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.ai.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 接口用例生成（对标 MeterSphere AI 生成接口用例）：输入接口定义，生成可执行的接口用例建议
 * （方法/路径/参数/断言），不自动落库，作为建议展示。
 */
@Service
public class AiApiCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiApiCaseGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public AiApiCaseGenerator(JdbcTemplate jdbc, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    /** 为单个接口生成用例建议。 */
    public Map<String, Object> generateForEndpoint(Long projectId, Long endpointId) {
        Map<String, Object> ep = jdbc.queryForMap("""
                SELECT e.id, e.method, e.path, e.name, e.summary,
                       e.params_json AS "params", e.request_body_json AS "requestBody",
                       e.response_schema_json AS "responseSchema"
                FROM api_endpoint e WHERE e.id = ? AND e.project_id = ?
                """, endpointId, projectId);
        return generate(projectId, ep);
    }

    /** 为多个接口批量生成（用于接口清单页一键生成）。 */
    public Map<String, Object> generateForProject(Long projectId) {
        List<Map<String, Object>> endpoints = jdbc.queryForList("""
                SELECT e.id, e.method, e.path, e.name, e.summary,
                       e.params_json AS "params", e.request_body_json AS "requestBody",
                       e.response_schema_json AS "responseSchema"
                FROM api_endpoint e WHERE e.project_id = ? ORDER BY e.id LIMIT 20
                """, projectId);
        List<Map<String, Object>> all = new ArrayList<>();
        boolean ai = false;
        for (Map<String, Object> ep : endpoints) {
            Map<String, Object> g = generate(projectId, ep);
            all.add(g);
            ai = ai || Boolean.TRUE.equals(g.get("aiGenerated"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpointCount", endpoints.size());
        out.put("aiGenerated", ai);
        out.put("model", modelRouter.hasUsableModel() ? modelRouter.getModelName() : null);
        out.put("results", all);
        return out;
    }

    private Map<String, Object> generate(Long projectId, Map<String, Object> ep) {
        String method = String.valueOf(ep.get("method"));
        String path = String.valueOf(ep.get("path"));
        String name = ep.get("name") != null ? ep.get("name").toString() : (method + " " + path);

        GeneratedApiCases generated = null;
        if (modelRouter.hasUsableModel()) {
            try {
                ChatClient client = modelRouter.clientFor("API_CASE_GENERATION");
                String prompt = promptLoader.fill("api-case-generation", Map.of(
                        "method", method,
                        "path", path,
                        "summary", ep.get("summary") != null ? ep.get("summary").toString() : "",
                        "params", ep.get("params") != null ? ep.get("params").toString() : "[]",
                        "requestBody", ep.get("requestBody") != null ? ep.get("requestBody").toString() : "{}",
                        "responseSchema", ep.get("responseSchema") != null ? ep.get("responseSchema").toString() : "{}"));
                generated = client.prompt().user(prompt).call().entity(GeneratedApiCases.class);
            } catch (Exception e) {
                log.warn("AI API case generation failed, using heuristic: {}", e.getMessage());
            }
        }

        List<Map<String, Object>> cases = generated != null && generated.cases() != null && !generated.cases().isEmpty()
                ? generated.cases() : heuristic(method, path, name);
        boolean ai = generated != null && generated.cases() != null && !generated.cases().isEmpty();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpointId", ep.get("id"));
        out.put("endpoint", name);
        out.put("method", method);
        out.put("path", path);
        out.put("cases", cases);
        out.put("aiGenerated", ai);
        return out;
    }

    private List<Map<String, Object>> heuristic(String method, String path, String name) {
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(caseItem("正常调用", method, path, true, "期望返回 2xx 且响应符合 schema"));
        cases.add(caseItem("非法参数", method, path, false, "期望返回 4xx 校验提示"));
        cases.add(caseItem("未授权访问", method, path, false, "期望返回 401/403"));
        return cases;
    }

    private Map<String, Object> caseItem(String title, String method, String path, boolean valid, String assertion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("method", method);
        m.put("path", path);
        m.put("valid", valid);
        m.put("assertion", assertion);
        return m;
    }

    public record GeneratedApiCases(List<Map<String, Object>> cases) {}
}