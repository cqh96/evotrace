package io.evotrace.server.testplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试报告（对标 MeterSphere 报告体系）：把计划报告落库、生成分享 token、提供报告详情
 * 与需求覆盖度统计。报告为只读快照，分享 token 免登录可读。
 */
@Service
public class TestReportService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final SecureRandom RAND = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TestPlanService planService;
    private final TestCaseService caseService;

    public TestReportService(JdbcTemplate jdbc, TestPlanService planService, TestCaseService caseService) {
        this.jdbc = jdbc;
        this.planService = planService;
        this.caseService = caseService;
    }

    /** 计划执行后生成报告（幂等：同一计划仅保留最新一条）。 */
    @Transactional
    public Map<String, Object> generateFromPlan(Long projectId, Long planId) {
        Map<String, Object> summary = planService.report(projectId, planId);
        String name = "测试报告 - " + summary.get("planName");
        String token = randomToken();
        Long id = jdbc.queryForObject("""
                INSERT INTO test_report(project_id, plan_id, name, status, summary_json, share_token)
                VALUES (?, ?, ?, 'DONE', ?::jsonb, ?) RETURNING id
                """, Long.class, projectId, planId, name,
                writeJson(summary), token);
        return detail(projectId, id);
    }

    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, plan_id AS "planId", name, status, created_by AS "createdBy",
                       created_at AS "createdAt"
                FROM test_report WHERE project_id = ? ORDER BY created_at DESC
                """, projectId);
    }

    public Map<String, Object> detail(Long projectId, Long reportId) {
        Map<String, Object> report = jdbc.queryForMap("""
                SELECT id, plan_id AS "planId", name, status, summary_json AS "summary",
                       share_token AS "shareToken", created_by AS "createdBy", created_at AS "createdAt"
                FROM test_report WHERE id = ? AND project_id = ?
                """, reportId, projectId);
        Map<String, Object> summary = readSummary(report.get("summary"));
        Long reqId = firstRequirementOfPlan(reportId);
        if (reqId != null) {
            summary.put("coverage", caseService.traceMatrix(projectId, reqId).get("coverage"));
        } else {
            // 计划未关联需求时给出零值覆盖度，避免 traceMatrix(null) 空查询抛 404
            summary.put("coverage", Map.of("total", 0, "passed", 0, "failed", 0, "pending", 0, "openBugs", 0));
        }
        report.put("summary", summary);
        return report;
    }

    /** 分享：用 token 免登录读取（跨项目只读）。 */
    public Map<String, Object> share(String token) {
        Map<String, Object> report = jdbc.queryForMap("""
                SELECT id, project_id AS "projectId", plan_id AS "planId", name, status,
                       summary_json AS "summary", created_at AS "createdAt"
                FROM test_report WHERE share_token = ?
                """, token);
        report.put("summary", readSummary(report.get("summary")));
        return report;
    }

    @Transactional
    public String refreshShareToken(Long projectId, Long reportId) {
        String token = randomToken();
        jdbc.update("UPDATE test_report SET share_token = ? WHERE id = ? AND project_id = ?",
                token, reportId, projectId);
        return token;
    }

    @Transactional
    public void delete(Long projectId, Long reportId) {
        jdbc.update("DELETE FROM test_report WHERE id = ? AND project_id = ?", reportId, projectId);
    }

    private Long firstRequirementOfPlan(Long reportId) {
        try {
            return jdbc.queryForObject("""
                    SELECT tc.requirement_id FROM test_report r
                    JOIN test_plan_item pi ON pi.plan_id = r.plan_id
                    JOIN test_case tc ON tc.id = pi.test_case_id
                    WHERE r.id = ? AND tc.requirement_id IS NOT NULL
                    ORDER BY pi.id LIMIT 1
                    """, Long.class, reportId);
        } catch (Exception e) {
            return null;
        }
    }

    /** pgjdbc 对 jsonb 列返回 PGobject（toString 即 JSON 文本），统一解析为 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readSummary(Object raw) {
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return readMap(raw == null ? null : String.valueOf(raw));
    }

    private Map<String, Object> readMap(String s) {
        try {
            return mapper.readValue(s, mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("报告序列化失败", e);
        }
    }

    private String randomToken() {
        byte[] b = new byte[24];
        RAND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}