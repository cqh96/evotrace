package io.evotrace.server.testplan;

import com.fasterxml.jackson.databind.JsonNode;
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
 * AI 测试用例生成器（借鉴 MeterSphere / PR-Agent 的测试建议）。
 * <p>输入 变更事件 + 可选需求，调用模型生成测试用例建议（不自动落库，作为建议展示）。
 * 模型不可用时回退到基于变更文件行数的启发式建议。</p>
 */
@Service
public class AiTestCaseGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiTestCaseGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public AiTestCaseGenerator(JdbcTemplate jdbc, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    /** 生成测试用例建议。 */
    public Map<String, Object> generate(Long projectId, String eventId, Long requirementId) {
        Map<String, Object> changes = loadChanges(projectId, eventId);
        String requirementText = requirementId != null ? loadRequirement(requirementId) : "(none)";

        GeneratedTests result = null;
        if (modelRouter.hasUsableModel()) {
            try {
                ChatClient client = modelRouter.clientFor("TEST_GENERATION");
                String prompt = promptLoader.fill("test-case-generation", Map.of(
                        "projectKey", String.valueOf(projectId),
                        "requirement", requirementText,
                        "changes", (String) changes.get("changesText")));
                result = client.prompt().user(prompt).call().entity(GeneratedTests.class);
            } catch (Exception e) {
                log.warn("AI test generation failed, using heuristic: {}", e.getMessage());
            }
        }

        if (result == null || result.testCases() == null || result.testCases().isEmpty()) {
            result = heuristic((String) changes.get("changesText"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", eventId);
        out.put("requirementId", requirementId);
        out.put("summary", result.summary());
        out.put("testCases", result.testCases());
        out.put("aiGenerated", result.aiGenerated());
        out.put("fileCount", changes.get("fileCount"));
        out.put("model", modelRouter.hasUsableModel() ? modelRouter.getModelName() : null);
        return out;
    }

    private Map<String, Object> loadChanges(Long projectId, String eventId) {
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT file_path AS "filePath", change_kind AS "changeKind",
                       add_lines AS "addLines", del_lines AS "delLines"
                FROM change_file WHERE event_id = ?
                """, eventId);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> f : files) {
            sb.append(String.format("- [%s] %s (+%d -%d)%n", f.get("changeKind"), f.get("filePath"),
                    f.get("addLines"), f.get("delLines")));
        }
        String text = sb.isEmpty() ? "(no file changes recorded)" : sb.toString();
        return Map.of("changesText", text, "fileCount", files.size());
    }

    private String loadRequirement(Long requirementId) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT title, description, acceptance_criteria FROM requirement WHERE id = ?",
                    requirementId);
            StringBuilder sb = new StringBuilder();
            if (row.get("title") != null) sb.append("标题: ").append(row.get("title")).append("\n");
            if (row.get("description") != null) sb.append("描述: ").append(row.get("description")).append("\n");
            if (row.get("acceptance_criteria") != null) sb.append("验收标准: ").append(row.get("acceptance_criteria"));
            return sb.isEmpty() ? "(requirement with no detail)" : sb.toString();
        } catch (Exception e) {
            return "(requirement not found)";
        }
    }

    private GeneratedTests heuristic(String changesText) {
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(caseItem("功能主流程验证", "FUNCTIONAL", "P0",
                "[{\"step\":\"前置条件准备并进入目标页面/接口\",\"expected\":\"正常加载\"},"
                        + "{\"step\":\"执行核心操作\",\"expected\":\"结果符合预期\"}]"));
        cases.add(caseItem("边界与异常输入", "FUNCTIONAL", "P1",
                "[{\"step\":\"传入空值/超长/非法格式数据\",\"expected\":\"给出合理校验提示，不崩溃\"}]"));
        cases.add(caseItem("回归验证", "REGRESSION", "P1",
                "[{\"step\":\"执行与变更相关的既有功能\",\"expected\":\"原有行为不受影响\"}]"));
        return new GeneratedTests(
                "针对变更涉及 " + changesText.split("\n").length + " 行文件的建议用例（AI 模型未配置或调用失败，采用启发式）",
                cases, false);
    }

    private Map<String, Object> caseItem(String title, String type, String priority, String stepsJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("testType", type);
        m.put("priority", priority);
        m.put("steps", stepsJson);
        return m;
    }

    /** 解析 AI 返回：部分模型可能返回 steps 为数组而非字符串，此处统一为 JSON 字符串。 */
    public record GeneratedTests(String summary, List<Map<String, Object>> testCases, boolean aiGenerated) {

        public GeneratedTests {
            if (testCases != null) {
                for (Map<String, Object> tc : testCases) {
                    Object steps = tc.get("steps");
                    tc.put("steps", steps instanceof String s ? s : toJson(steps));
                }
            }
        }

        private static String toJson(Object steps) {
            try {
                return steps == null ? "[]" : new ObjectMapper().writeValueAsString(steps);
            } catch (Exception e) {
                return "[]";
            }
        }
    }
}