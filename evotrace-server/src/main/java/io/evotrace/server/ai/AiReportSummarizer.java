package io.evotrace.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 报告摘要（对标 MeterSphere AI 报告分析）：对一次测试计划的执行结果生成自然语言摘要，
 * 指出风险点与改进建议。模型不可用时回退到确定性模板。
 */
@Service
public class AiReportSummarizer {

    private static final Logger log = LoggerFactory.getLogger(AiReportSummarizer.class);

    private final JdbcTemplate jdbc;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public AiReportSummarizer(JdbcTemplate jdbc, ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    /** 生成报告摘要：输入计划执行报告摘要 → 返回 markdown 摘要文本。 */
    public Map<String, Object> summarize(Long projectId, Long planId) {
        Map<String, Object> report = jdbc.queryForMap("""
                SELECT tp.name AS "planName", tp.target_version AS "targetVersion",
                       tp.status, tp.from_version AS "fromVersion"
                FROM test_plan tp WHERE tp.id = ? AND tp.project_id = ?
                """, planId, projectId);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT pi.status, pi.result_detail AS "resultDetail", tc.title, tc.priority, tc.test_type AS "testType"
                FROM test_plan_item pi LEFT JOIN test_case tc ON tc.id = pi.test_case_id
                WHERE pi.plan_id = ? ORDER BY pi.sort_order
                """, planId);

        long passed = items.stream().filter(i -> "PASSED".equals(i.get("status"))).count();
        long failed = items.stream().filter(i -> "FAILED".equals(i.get("status"))).count();
        long total = items.size();
        List<String> fails = items.stream()
                .filter(i -> "FAILED".equals(i.get("status")))
                .map(i -> "- [" + i.get("priority") + "] " + i.get("title") + " (" + i.get("testType") + ")")
                .toList();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("planId", planId);
        out.put("planName", report.get("planName"));
        out.put("total", total);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("passRate", total > 0 ? (int) Math.round(passed * 100.0 / total) : 0);
        out.put("failCases", fails);
        out.put("summary", generateText(report, passed, failed, total, fails));
        out.put("aiGenerated", modelRouter.hasUsableModel());
        out.put("model", modelRouter.hasUsableModel() ? modelRouter.getModelName() : null);
        return out;
    }

    private String generateText(Map<String, Object> report, long passed, long failed, long total, List<String> fails) {
        String prompt = promptLoader.fill("report-summary", Map.of(
                "planName", String.valueOf(report.get("planName")),
                "targetVersion", String.valueOf(report.getOrDefault("targetVersion", "")),
                "total", String.valueOf(total),
                "passed", String.valueOf(passed),
                "failed", String.valueOf(failed),
                "failCases", fails.isEmpty() ? "(none)" : String.join("\n", fails)));
        if (!modelRouter.hasUsableModel()) {
            return fallback(passed, failed, total, fails);
        }
        try {
            ChatClient client = modelRouter.clientFor("TEST_SUMMARY");
            AiReportSummarizer.ReportResult r = client.prompt().user(prompt).call().entity(AiReportSummarizer.ReportResult.class);
            if (r != null && r.summary() != null && !r.summary().isBlank()) return r.summary();
            return fallback(passed, failed, total, fails);
        } catch (Exception e) {
            log.warn("AI report summary failed, using template: {}", e.getMessage());
            return fallback(passed, failed, total, fails);
        }
    }

    private String fallback(long passed, long failed, long total, List<String> fails) {
        StringBuilder sb = new StringBuilder();
        sb.append("**计划执行摘要**：共 ").append(total).append(" 条用例，通过 ")
                .append(passed).append("，失败 ").append(failed).append("，通过率 ")
                .append(total > 0 ? Math.round(passed * 100.0 / total) : 0).append("%。");
        if (failed == 0) {
            sb.append("本次执行全部通过，质量良好。");
        } else {
            sb.append("存在 ").append(failed).append(" 条失败用例，建议优先修复：\n");
            fails.forEach(f -> sb.append(f).append("\n"));
        }
        return sb.toString();
    }

    public record ReportResult(String summary) {}
}