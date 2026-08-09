package io.evotrace.server.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 研发效能度量（对标 TAPD 四大维度：进度 / 产能 / 速率 / 质量）。
 * 基于已在库的 change_event / requirement / bug_ticket / release / test_execution 实时计算，
 * 并支持对指定周期快照到 dev_metric 表用于趋势对比。
 */
@Service
public class DevMetricsService {

    private final JdbcTemplate jdbc;

    public DevMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 汇总指标：交付率 / 吞吐 / 缺陷逃逸 / 发布周期 / 需求周期 / 质量。 */
    public Map<String, Object> overview(Long projectId) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ---- 需求交付率：DONE / 总数（近 90 天） ----
        Map<String, Object> req = jdbc.queryForMap("""
                SELECT count(*) AS "total",
                       count(*) FILTER (WHERE status = 'DONE') AS "done"
                FROM requirement
                WHERE project_id = ? AND created_at >= now() - interval '90 days'
                """, projectId);
        long reqTotal = ((Number) req.get("total")).longValue();
        long reqDone = ((Number) req.get("done")).longValue();
        out.put("requirementDeliveryRate", reqTotal > 0 ? Math.round(reqDone * 100.0 / reqTotal) : 0);
        out.put("requirementDone", reqDone);
        out.put("requirementTotal", reqTotal);

        // ---- 变更吞吐：近 30 天提交数 / 平均每日 ----
        long changes30 = ((Number) jdbc.queryForObject("""
                SELECT count(*) FROM change_event
                WHERE project_id = ? AND occurred_at >= now() - interval '30 days'
                """, Long.class, projectId)).longValue();
        out.put("changeThroughput", changes30);
        out.put("avgDailyChanges", Math.round(changes30 / 30.0 * 10) / 10.0);

        // ---- 缺陷逃逸率：线上(已发布)缺陷 / 全部缺陷 ----
        Map<String, Object> bug = jdbc.queryForMap("""
                SELECT count(*) AS "total",
                       count(*) FILTER (WHERE found_version IN (
                           SELECT version FROM release WHERE project_id = ?)
                           OR source = 'PROD') AS "escaped"
                FROM bug_ticket WHERE project_id = ?
                """, projectId, projectId);
        long bugTotal = ((Number) bug.get("total")).longValue();
        long bugEscaped = ((Number) bug.get("escaped")).longValue();
        out.put("bugEscapeRate", bugTotal > 0 ? Math.round(bugEscaped * 100.0 / bugTotal) : 0);
        out.put("bugTotal", bugTotal);
        out.put("bugOpen", ((Number) jdbc.queryForObject(
                "SELECT count(*) FROM bug_ticket WHERE project_id = ? AND status IN ('OPEN','IN_PROGRESS','REOPENED')",
                Long.class, projectId)).longValue());

        // ---- 发布周期：两次发布平均间隔天数 ----
        List<Map<String, Object>> releases = jdbc.queryForList("""
                SELECT released_at FROM release
                WHERE project_id = ? AND status = 'RELEASED' ORDER BY released_at DESC LIMIT 10
                """, projectId);
        out.put("releaseCount", releases.size());
        out.put("avgReleaseCycleDays", avgIntervalDays(releases));

        // ---- 需求平均交付周期：从 created_at 到 DONE ----
        out.put("avgRequirementCycleDays", avgRequirementCycleDays(projectId));

        // ---- 质量：用例通过率 ----
        Map<String, Object> exec = jdbc.queryForMap("""
                SELECT count(*) AS "total",
                       count(*) FILTER (WHERE status = 'PASSED') AS "passed",
                       count(*) FILTER (WHERE status = 'FAILED') AS "failed"
                FROM test_execution WHERE created_at >= now() - interval '90 days'
                  AND test_case_id IN (SELECT id FROM test_case WHERE project_id = ?)
                """, projectId);
        long execTotal = ((Number) exec.get("total")).longValue();
        long execPassed = ((Number) exec.get("passed")).longValue();
        out.put("testPassRate", execTotal > 0 ? Math.round(execPassed * 100.0 / execTotal) : 0);
        out.put("testExecutions", execTotal);
        return out;
    }

    /** 近 N 天趋势：提交 / 需求完成 / 缺陷新增 / 用例执行。 */
    public List<Map<String, Object>> trend(Long projectId, int days) {
        int d = Math.min(Math.max(days, 7), 90);
        return jdbc.queryForList("""
                SELECT to_char(day, 'MM-DD') AS "day",
                       coalesce(c.cnt, 0) AS "changes",
                       coalesce(r.cnt, 0) AS "requirements",
                       coalesce(b.cnt, 0) AS "bugs",
                       coalesce(t.cnt, 0) AS "executions"
                FROM generate_series(
                        date_trunc('day', now()) - (? - 1) * interval '1 day',
                        date_trunc('day', now()), interval '1 day') AS day
                LEFT JOIN (SELECT date_trunc('day', occurred_at) d, count(*) cnt
                           FROM change_event WHERE project_id = ? GROUP BY 1) c ON c.d = day
                LEFT JOIN (SELECT date_trunc('day', created_at) d, count(*) cnt
                           FROM requirement WHERE project_id = ? GROUP BY 1) r ON r.d = day
                LEFT JOIN (SELECT date_trunc('day', created_at) d, count(*) cnt
                           FROM bug_ticket WHERE project_id = ? GROUP BY 1) b ON b.d = day
                LEFT JOIN (SELECT date_trunc('day', created_at) d, count(*) cnt
                           FROM test_execution WHERE test_case_id IN
                               (SELECT id FROM test_case WHERE project_id = ?) GROUP BY 1) t ON t.d = day
                ORDER BY day
                """, d, projectId, projectId, projectId, projectId);
    }

    /** 缺陷分布：按严重级别 × 状态。 */
    public List<Map<String, Object>> bugDistribution(Long projectId) {
        return jdbc.queryForList("""
                SELECT severity, status, count(*) AS count
                FROM bug_ticket WHERE project_id = ?
                GROUP BY severity, status ORDER BY severity
                """, projectId);
    }

    /** 需求状态分布 + 各状态平均驻留天数。 */
    public List<Map<String, Object>> requirementFlow(Long projectId) {
        return jdbc.queryForList("""
                SELECT h.status AS "status", count(*) AS "entries",
                       round(avg(extract(epoch FROM (coalesce(h.left_at, now()) - h.entered_at)) / 86400.0), 1) AS "avgDays"
                FROM requirement_status_history h
                JOIN requirement r ON r.id = h.requirement_id AND r.project_id = ?
                GROUP BY h.status ORDER BY h.status
                """, projectId);
    }

    /** 快照当前指标到 dev_metric（周期=YYYY-MM）。 */
    public Map<String, Object> snapshot(Long projectId) {
        String period = LocalDate.now().toString().substring(0, 7);
        Map<String, Object> payload = overview(projectId);
        jdbc.update("""
                INSERT INTO dev_metric(project_id, period_key, payload) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (project_id, period_key)
                DO UPDATE SET payload = EXCLUDED.payload, created_at = now()
                """, projectId, period,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload).toString());
        return Map.of("success", true, "period", period, "metrics", payload);
    }

    /** 历史快照列表（用于趋势对比）。 */
    public List<Map<String, Object>> history(Long projectId) {
        return jdbc.queryForList("""
                SELECT period_key AS "period", payload, created_at AS "createdAt"
                FROM dev_metric WHERE project_id = ? ORDER BY period_key DESC LIMIT 12
                """, projectId);
    }

    private Object avgIntervalDays(List<Map<String, Object>> releases) {
        if (releases.size() < 2) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < releases.size() - 1; i++) {
            Object a = releases.get(i).get("released_at");
            Object b = releases.get(i + 1).get("released_at");
            if (a == null || b == null) {
                continue;
            }
            LocalDateTime ta = LocalDateTime.parse(a.toString().substring(0, 19).replace(' ', 'T'));
            LocalDateTime tb = LocalDateTime.parse(b.toString().substring(0, 19).replace(' ', 'T'));
            sum += Math.abs(java.time.Duration.between(tb, ta).toHours()) / 24.0;
            n++;
        }
        return n > 0 ? Math.round((sum / n) * 10) / 10.0 : null;
    }

    private Object avgRequirementCycleDays(Long projectId) {
        try {
            return jdbc.queryForObject("""
                    SELECT round(avg(extract(epoch FROM (r.updated_at - r.created_at)) / 86400.0), 1)
                    FROM requirement r
                    WHERE r.project_id = ? AND r.status = 'DONE'
                    """, Object.class, projectId);
        } catch (Exception e) {
            return null;
        }
    }
}