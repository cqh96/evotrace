package io.evotrace.server.trace;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求 / 版本完整度评分（docs/10 §8.4.4 / §8.4.5，A 期固定权重）。
 */
@Service
public class CompletenessScorer {

    private final JdbcTemplate jdbc;

    public CompletenessScorer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 需求完整度：HAS_CODE 25 / HAS_TEST_CASE 20 / HAS_EXECUTION 15 / NO_BLOCKING_BUG 25 / SHIPPED_OR_TARGET 10 / GATE_PASS_IF_RELEASED 5。 */
    public Map<String, Object> scoreRequirement(Long projectId, Long requirementId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        String reqId = String.valueOf(requirementId);

        // HAS_CODE：存在 IMPLEMENTS 边（CHANGE→本需求）
        int codeCount = intOf("""
                SELECT count(*) FROM artifact_link al
                WHERE al.project_id = ? AND al.from_type = 'CHANGE_EVENT'
                  AND al.to_type = 'REQUIREMENT' AND al.to_id = ?
                  AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                """, projectId, reqId);
        checks.add(check("HAS_CODE", codeCount > 0, 25, codeCount > 0 ? codeCount + " commits" : "无代码关联"));

        // HAS_TEST_CASE：test_case.requirement_id = 本需求
        int caseCount = intOf("SELECT count(*) FROM test_case WHERE requirement_id = ?", requirementId);
        checks.add(check("HAS_TEST_CASE", caseCount > 0, 20, caseCount > 0 ? caseCount + " cases" : "无用例"));

        // HAS_EXECUTION：上述用例 14 日内有 execution
        int execCount = intOf("""
                SELECT count(DISTINCT te.id) FROM test_execution te
                JOIN test_case tc ON tc.id = te.test_case_id
                WHERE tc.requirement_id = ? AND te.executed_at >= now() - interval '14 days'
                """, requirementId);
        checks.add(check("HAS_EXECUTION", execCount > 0, 15,
                execCount > 0 ? "有 " + execCount + " 次执行" : "无近 14 天执行"));

        // NO_BLOCKING_BUG：无 severity∈(P0,P1) 且未关闭
        int blockingBugs = intOf("""
                SELECT count(*) FROM bug_ticket
                WHERE requirement_id = ? AND severity IN ('P0','P1')
                  AND status NOT IN ('CLOSED','VERIFIED')
                """, requirementId);
        checks.add(check("NO_BLOCKING_BUG", blockingBugs == 0, 25,
                blockingBugs > 0 ? blockingBugs + " open P0/P1" : "无阻塞缺陷"));

        // SHIPPED_OR_TARGET：有 SHIPPED_IN 或 target_version 非空
        int shipped = intOf("""
                SELECT count(*) FROM artifact_link al
                WHERE al.project_id = ? AND al.from_type = 'REQUIREMENT' AND al.from_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                """, projectId, reqId);
        String targetVersion = strOf("SELECT target_version FROM requirement WHERE id = ?", requirementId);
        boolean shippedOrTarget = shipped > 0 || (targetVersion != null && !targetVersion.isBlank());
        checks.add(check("SHIPPED_OR_TARGET", shippedOrTarget, 10,
                shippedOrTarget ? (targetVersion != null && !targetVersion.isBlank() ? "target " + targetVersion : "已发布") : "未发布且无目标版本"));

        // GATE_PASS_IF_RELEASED：若已 SHIPPED_IN 则最新门禁 PASSED，否则计通过
        boolean gatePassed = true;
        String gateDetail = "N/A";
        if (shipped > 0) {
            Long latestReleaseId = longOrNull("""
                    SELECT r.id FROM artifact_link al
                    JOIN release r ON r.id = al.to_id::bigint
                    WHERE al.project_id = ? AND al.from_type = 'REQUIREMENT' AND al.from_id = ?
                      AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                    ORDER BY r.released_at DESC LIMIT 1
                    """, projectId, reqId);
            if (latestReleaseId != null) {
                int passed = intOf("""
                        SELECT count(*) FROM quality_gate WHERE release_id = ? AND status = 'PASSED'
                        """, latestReleaseId);
                gatePassed = passed > 0;
                gateDetail = gatePassed ? "门禁 PASSED" : "门禁未通过";
            } else {
                gatePassed = true;
                gateDetail = "N/A";
            }
        }
        checks.add(check("GATE_PASS_IF_RELEASED", gatePassed, 5, gateDetail));

        return summarize(checks);
    }

    /** 版本完整度：HAS_CHANGESET 20 / REQ_LINKED 20 / NO_BLOCKING_BUG 30 / GATE_CHECKED 15 / GATE_PASSED 15。 */
    public Map<String, Object> scoreRelease(Long projectId, Long releaseId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        String relId = String.valueOf(releaseId);

        int changeset = intOf("""
                SELECT count(*) FROM artifact_link al
                WHERE al.project_id = ? AND al.from_type = 'CHANGE_EVENT'
                  AND al.to_type = 'RELEASE' AND al.to_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                """, projectId, relId);
        checks.add(check("HAS_CHANGESET", changeset > 0, 20, changeset + " 变更"));

        int reqLinked = intOf("""
                SELECT count(*) FROM artifact_link al
                WHERE al.project_id = ? AND al.from_type = 'REQUIREMENT'
                  AND al.to_type = 'RELEASE' AND al.to_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                """, projectId, relId);
        checks.add(check("REQ_LINKED", reqLinked > 0, 20, reqLinked + " 需求"));

        String version = strOf("SELECT version FROM release WHERE id = ?", releaseId);
        int blockingBugs = 0;
        if (version != null) {
            blockingBugs = intOf("""
                    SELECT count(*) FROM bug_ticket
                    WHERE project_id = ? AND fixed_version = ? AND severity IN ('P0','P1')
                      AND status NOT IN ('CLOSED','VERIFIED')
                    """, projectId, version);
        }
        checks.add(check("NO_BLOCKING_BUG", blockingBugs == 0, 30,
                blockingBugs > 0 ? blockingBugs + " open P0/P1" : "无阻塞缺陷"));

        int gateCount = intOf("SELECT count(*) FROM quality_gate WHERE release_id = ?", releaseId);
        int gatePassed = intOf("SELECT count(*) FROM quality_gate WHERE release_id = ? AND status = 'PASSED'", releaseId);
        checks.add(check("GATE_CHECKED", gateCount > 0, 15, gateCount > 0 ? "已检查" : "未检查"));
        checks.add(check("GATE_PASSED", gatePassed > 0, 15, gatePassed > 0 ? "通过" : "未通过"));

        return summarize(checks);
    }

    private static Map<String, Object> check(String key, boolean passed, int weight, String detail) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("passed", passed);
        c.put("weight", weight);
        c.put("detail", detail);
        return c;
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> checks) {
        int score = 0;
        for (Map<String, Object> c : checks) {
            if (Boolean.TRUE.equals(c.get("passed"))) {
                score += ((Number) c.get("weight")).intValue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("checks", checks);
        return result;
    }

    private int intOf(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v != null ? v : 0;
    }

    private String strOf(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, String.class, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Long longOrNull(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, Long.class, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}