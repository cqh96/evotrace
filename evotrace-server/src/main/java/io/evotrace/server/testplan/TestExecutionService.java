package io.evotrace.server.testplan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test execution records: standalone executions (test_execution) and
 * plan executions (test_plan_item with executed_at) are merged via UNION ALL
 * into one timeline, plus daily trend aggregations for the quality dashboard.
 */
@Service
public class TestExecutionService {

    private static final Set<String> RESULT_STATUSES = Set.of("PASSED", "FAILED", "BLOCKED", "SKIPPED");

    private final JdbcTemplate jdbc;

    public TestExecutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Long record(Long projectId, Map<String, Object> data) {
        Object caseId = data.get("testCaseId");
        if (caseId == null) {
            throw new IllegalArgumentException("缺少 testCaseId");
        }
        String status = String.valueOf(data.getOrDefault("status", "PASSED"));
        if (!RESULT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法执行状态: " + status);
        }
        // planItemId 命中时更新计划项（外部 runner 回传路径）
        if (data.get("planItemId") != null) {
            Long itemId = ((Number) data.get("planItemId")).longValue();
            int updated = jdbc.update("""
                    UPDATE test_plan_item SET status = ?, executor = ?, result_detail = ?, executed_at = now()
                    WHERE id = ?
                    """, status, data.get("executor"), data.get("resultDetail"), itemId);
            if (updated == 0) {
                throw new IllegalArgumentException("计划项不存在: " + itemId);
            }
            return itemId;
        }
        return jdbc.queryForObject("""
                INSERT INTO test_execution(test_case_id, release_id, executor, status, result_detail, executed_at)
                VALUES (?, ?, ?, ?, ?, COALESCE(?::timestamptz, now())) RETURNING id
                """, Long.class, caseId, data.get("releaseId"), data.get("executor"),
                status, data.get("resultDetail"), data.get("executedAt"));
    }

    /** Standalone + plan executions merged into one timeline (page size capped). */
    public Map<String, Object> list(Long projectId, Long releaseId, String status,
                                    String from, String to, int page, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE tc.project_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (releaseId != null) {
            where.append(" AND te.\"releaseId\" = ?");
            args.add(String.valueOf(releaseId));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND te.status = ?");
            args.add(status);
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND te.executed_at >= ?::timestamptz");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND te.executed_at <= ?::timestamptz");
            args.add(to);
        }

        String sql = """
                SELECT * FROM (
                    SELECT te.id, te.test_case_id AS "testCaseId", te.status, te.executor,
                           te.result_detail AS "resultDetail", te.executed_at AS "executedAt",
                           tc.title, tc.priority, tc.test_type AS "testType",
                           NULL AS "planTitle", 'STANDALONE' AS source, te.release_id::text AS "releaseId"
                    FROM test_execution te JOIN test_case tc ON tc.id = te.test_case_id
                    UNION ALL
                    SELECT pi.id, pi.test_case_id AS "testCaseId", pi.status, pi.executor,
                           pi.result_detail AS "resultDetail", pi.executed_at AS "executedAt",
                           tc.title, tc.priority, tc.test_type AS "testType",
                           tp.name AS "planTitle", 'PLAN' AS source, tp.target_version AS "releaseId"
                    FROM test_plan_item pi
                    JOIN test_case tc ON tc.id = pi.test_case_id
                    JOIN test_plan tp ON tp.id = pi.plan_id
                    WHERE pi.executed_at IS NOT NULL
                ) te JOIN test_case tc ON tc.id = te."testCaseId"
                """ + where + " ORDER BY te.\"executedAt\" DESC LIMIT ? OFFSET ?";

        List<Object> allArgs = new java.util.ArrayList<>(args);
        allArgs.add(Math.min(pageSize, 100));
        allArgs.add(Math.max(page - 1, 0) * pageSize);

        int total = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT te.id, te.test_case_id AS "testCaseId" FROM test_execution te
                    UNION ALL
                    SELECT pi.id, pi.test_case_id AS "testCaseId" FROM test_plan_item pi
                    JOIN test_case tc ON tc.id = pi.test_case_id
                    JOIN test_plan tp ON tp.id = pi.plan_id
                    WHERE pi.executed_at IS NOT NULL
                ) te JOIN test_case tc ON tc.id = te."testCaseId"
                """ + where, Integer.class, args.toArray());

        return Map.of("total", total, "list", jdbc.queryForList(sql, allArgs.toArray()));
    }

    /** Daily execution trend (last N days, zero-filled). */
    public List<Map<String, Object>> executionTrend(Long projectId, int days) {
        return jdbc.queryForList("""
                SELECT d.day, COALESCE(t.total, 0) AS total, COALESCE(t.passed, 0) AS passed,
                       COALESCE(t.failed, 0) AS failed
                FROM generate_series(CURRENT_DATE - (? - 1)::int, CURRENT_DATE, interval '1 day') AS d(day)
                LEFT JOIN (
                    SELECT date(e.executed_at) AS day,
                           count(*) AS total,
                           count(*) FILTER (WHERE e.status = 'PASSED') AS passed,
                           count(*) FILTER (WHERE e.status = 'FAILED') AS failed
                    FROM (
                        SELECT te.executed_at, te.status FROM test_execution te
                        JOIN test_case tc ON tc.id = te.test_case_id WHERE tc.project_id = ?
                        UNION ALL
                        SELECT pi.executed_at, pi.status FROM test_plan_item pi
                        JOIN test_case tc ON tc.id = pi.test_case_id WHERE tc.project_id = ?
                        AND pi.executed_at IS NOT NULL
                    ) e
                    GROUP BY date(e.executed_at)
                ) t ON t.day = d.day
                ORDER BY d.day
                """, days, projectId, projectId);
    }

    /** Daily bug trend by severity (last N days). */
    public List<Map<String, Object>> bugTrend(Long projectId, int days) {
        return jdbc.queryForList("""
                SELECT d.day, COALESCE(b.p0, 0) AS p0, COALESCE(b.p1, 0) AS p1,
                       COALESCE(b.p2, 0) AS p2, COALESCE(b.p3, 0) AS p3
                FROM generate_series(CURRENT_DATE - (? - 1)::int, CURRENT_DATE, interval '1 day') AS d(day)
                LEFT JOIN (
                    SELECT date(created_at) AS day,
                           count(*) FILTER (WHERE severity = 'P0') AS p0,
                           count(*) FILTER (WHERE severity = 'P1') AS p1,
                           count(*) FILTER (WHERE severity = 'P2') AS p2,
                           count(*) FILTER (WHERE severity = 'P3') AS p3
                    FROM bug_ticket WHERE project_id = ? AND created_at >= CURRENT_DATE - (? - 1)::int
                    GROUP BY date(created_at)
                ) b ON b.day = d.day
                ORDER BY d.day
                """, days, projectId, days);
    }
}
