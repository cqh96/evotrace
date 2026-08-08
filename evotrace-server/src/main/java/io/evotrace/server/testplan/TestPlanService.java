package io.evotrace.server.testplan;

import io.evotrace.server.testing.TestRecommendationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test plan orchestration: DRAFT → RUNNING → DONE lifecycle, plan items with
 * per-item execution results, plan report, and the recommendation → plan
 * closed loop (createFromRecommendation).
 */
@Service
public class TestPlanService {

    /** Legal plan status transitions. */
    private static final Map<String, Set<String>> PLAN_TRANSITIONS = Map.of(
            "DRAFT", Set.of("RUNNING"),
            "RUNNING", Set.of("DONE", "DRAFT"),
            "DONE", Set.of("RUNNING")
    );

    /** Result statuses allowed for item execution. */
    private static final Set<String> RESULT_STATUSES = Set.of("PASSED", "FAILED", "BLOCKED", "SKIPPED");

    private static final List<String> PRIORITY_ORDER = List.of("P0", "P1", "P2", "P3");

    private final JdbcTemplate jdbc;
    private final TestRecommendationEngine recommendationEngine;

    public TestPlanService(JdbcTemplate jdbc, TestRecommendationEngine recommendationEngine) {
        this.jdbc = jdbc;
        this.recommendationEngine = recommendationEngine;
    }

    public List<Map<String, Object>> list(Long projectId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT tp.id, tp.name, tp.target_version AS "targetVersion", tp.from_version AS "fromVersion",
                       tp.status, tp.created_by AS "createdBy", tp.created_at AS "createdAt",
                       (SELECT count(*) FROM test_plan_item pi WHERE pi.plan_id = tp.id) AS "total",
                       (SELECT count(*) FROM test_plan_item pi WHERE pi.plan_id = tp.id AND pi.status = 'PASSED') AS "passed",
                       (SELECT count(*) FROM test_plan_item pi WHERE pi.plan_id = tp.id AND pi.status = 'FAILED') AS "failed",
                       (SELECT count(*) FROM test_plan_item pi WHERE pi.plan_id = tp.id AND pi.status IN ('PASSED','FAILED','BLOCKED','SKIPPED')) AS "executed"
                FROM test_plan tp WHERE tp.project_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>(List.of(projectId));
        if (status != null && !status.isBlank()) {
            sql.append(" AND tp.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY tp.created_at DESC");
        List<Map<String, Object>> plans = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> p : plans) {
            int total = ((Number) p.get("total")).intValue();
            int executed = ((Number) p.get("executed")).intValue();
            p.put("passRate", total > 0 ? executed * 100 / total : 0);
            p.put("progress", total > 0 ? executed * 100 / total : 0);
        }
        return plans;
    }

    @Transactional
    public Long create(Long projectId, Map<String, Object> data) {
        String name = data.get("name") != null ? data.get("name").toString() : "未命名测试计划";
        return jdbc.queryForObject("""
                INSERT INTO test_plan(project_id, name, description, target_version, from_version, status, executor, created_by)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?) RETURNING id
                """, Long.class, projectId, name, data.get("description"), data.get("targetVersion"),
                data.get("fromVersion"), data.get("executor"), data.get("createdBy"));
    }

    @Transactional
    public void update(Long projectId, Long planId, Map<String, Object> data) {
        StringBuilder set = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        if (data.get("name") != null) { set.append("name = ?, "); args.add(data.get("name")); }
        if (data.get("description") != null) { set.append("description = ?, "); args.add(data.get("description")); }
        if (data.get("targetVersion") != null) { set.append("target_version = ?, "); args.add(data.get("targetVersion")); }
        if (data.get("executor") != null) { set.append("executor = ?, "); args.add(data.get("executor")); }
        if (set.length() == 0) return;
        set.append("updated_at = now()");
        args.add(planId);
        args.add(projectId);
        jdbc.update("UPDATE test_plan SET " + set + " WHERE id = ? AND project_id = ?", args.toArray());
    }

    @Transactional
    public void delete(Long projectId, Long planId) {
        String status = getPlanStatus(planId);
        if ("DONE".equals(status)) {
            throw new IllegalArgumentException("已完成的测试计划保留报告，不可删除");
        }
        jdbc.update("DELETE FROM test_plan WHERE id = ? AND project_id = ?", planId, projectId);
    }

    @Transactional
    public void updateStatus(Long projectId, Long planId, String toStatus) {
        String from = getPlanStatus(planId);
        if (!PLAN_TRANSITIONS.getOrDefault(from, Set.of()).contains(toStatus)) {
            throw new IllegalArgumentException("非法计划状态流转: " + from + " → " + toStatus);
        }
        jdbc.update("UPDATE test_plan SET status = ?, updated_at = now() WHERE id = ?", toStatus, planId);
    }

    public Map<String, Object> detail(Long projectId, Long planId) {
        Map<String, Object> plan = jdbc.queryForMap("""
                SELECT * FROM test_plan WHERE id = ? AND project_id = ?
                """, planId, projectId);
        plan.put("items", jdbc.queryForList("""
                SELECT pi.id, pi.test_case_id AS "testCaseId", pi.sort_order AS "sortOrder",
                       pi.status, pi.executor, pi.result_detail AS "resultDetail",
                       pi.executed_at AS "executedAt",
                       tc.title, tc.priority, tc.test_type AS "testType",
                       tc.steps, tc.related_files AS "relatedFiles", tc.related_apis AS "relatedApis"
                FROM test_plan_item pi JOIN test_case tc ON tc.id = pi.test_case_id
                WHERE pi.plan_id = ? ORDER BY pi.sort_order, pi.id
                """, planId));
        return plan;
    }

    @Transactional
    public int addItems(Long projectId, Long planId, List<Long> testCaseIds) {
        String status = getPlanStatus(planId);
        if ("DONE".equals(status)) {
            throw new IllegalArgumentException("已完成计划不可追加用例");
        }
        Integer maxOrder = jdbc.queryForObject(
                "SELECT COALESCE(max(sort_order), -1) FROM test_plan_item WHERE plan_id = ?", Integer.class, planId);
        int order = maxOrder + 1;
        int added = 0;
        for (Long caseId : testCaseIds) {
            int updated = jdbc.update("""
                    INSERT INTO test_plan_item(plan_id, test_case_id, sort_order, status)
                    VALUES (?, ?, ?, 'PENDING')
                    ON CONFLICT (plan_id, test_case_id) DO NOTHING
                    """, planId, caseId, order++);
            added += updated;
        }
        return added;
    }

    @Transactional
    public void removeItem(Long projectId, Long planId, Long itemId) {
        String status = getPlanStatus(planId);
        if ("DONE".equals(status)) {
            throw new IllegalArgumentException("已完成计划不可移除用例");
        }
        String itemStatus = jdbc.queryForObject(
                "SELECT status FROM test_plan_item WHERE id = ? AND plan_id = ?", String.class, itemId, planId);
        if (!"PENDING".equals(itemStatus)) {
            throw new IllegalArgumentException("仅未执行的用例可移除，已执行请重置状态");
        }
        jdbc.update("DELETE FROM test_plan_item WHERE id = ? AND plan_id = ?", itemId, planId);
    }

    /** 轻量排序：将 itemId 与其相邻项（direction=UP/DOWN）交换 sort_order，实现拖拽式编排。 */
    @Transactional
    public void reorderItem(Long projectId, Long planId, Long itemId, String direction) {
        String status = getPlanStatus(planId);
        if ("DONE".equals(status)) {
            throw new IllegalArgumentException("已完成计划不可调整顺序");
        }
        List<Map<String, Object>> items = detail(projectId, planId).get("items") == null
                ? List.of()
                : (List<Map<String, Object>>) detail(projectId, planId).get("items");
        int cur = -1;
        for (int i = 0; i < items.size(); i++) {
            if (itemId.equals(items.get(i).get("id"))) { cur = i; break; }
        }
        if (cur < 0) {
            throw new IllegalArgumentException("计划项不存在");
        }
        int target = "UP".equalsIgnoreCase(direction) ? cur - 1 : cur + 1;
        if (target < 0 || target >= items.size()) {
            return; // 已在边界
        }
        Map<String, Object> a = items.get(cur);
        Map<String, Object> b = items.get(target);
        jdbc.update("UPDATE test_plan_item SET sort_order = ? WHERE id = ? AND plan_id = ?",
                ((Number) b.get("sortOrder")).intValue(), a.get("id"), planId);
        jdbc.update("UPDATE test_plan_item SET sort_order = ? WHERE id = ? AND plan_id = ?",
                ((Number) a.get("sortOrder")).intValue(), b.get("id"), planId);
    }

    @Transactional
    public void executeItem(Long projectId, Long planId, Long itemId,
                            String status, String executor, String resultDetail) {
        if (!RESULT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法执行结果状态: " + status);
        }
        String planStatus = getPlanStatus(planId);
        if ("DONE".equals(planStatus)) {
            throw new IllegalArgumentException("已完成计划不可再执行");
        }
        jdbc.update("""
                UPDATE test_plan_item SET status = ?, executor = ?, result_detail = ?, executed_at = now()
                WHERE id = ? AND plan_id = ?
                """, status, executor, resultDetail, itemId, planId);
    }

    public Map<String, Object> report(Long projectId, Long planId) {
        Map<String, Object> plan = jdbc.queryForMap("""
                SELECT * FROM test_plan WHERE id = ? AND project_id = ?
                """, planId, projectId);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT pi.id, pi.status, pi.executor, pi.executed_at AS "executedAt",
                       pi.result_detail AS "resultDetail", tc.title, tc.priority, tc.test_type AS "testType"
                FROM test_plan_item pi JOIN test_case tc ON tc.id = pi.test_case_id
                WHERE pi.plan_id = ? ORDER BY pi.sort_order
                """, planId);
        long total = items.size();
        long passed = items.stream().filter(i -> "PASSED".equals(i.get("status"))).count();
        long failed = items.stream().filter(i -> "FAILED".equals(i.get("status"))).count();
        long blocked = items.stream().filter(i -> "BLOCKED".equals(i.get("status"))).count();
        long skipped = items.stream().filter(i -> "SKIPPED".equals(i.get("status"))).count();
        long pending = items.stream().filter(i -> "PENDING".equals(i.get("status"))).count();
        List<Map<String, Object>> failCases = items.stream()
                .filter(i -> "FAILED".equals(i.get("status")))
                .map(i -> Map.of(
                        "title", i.get("title"),
                        "priority", i.get("priority"),
                        "executor", i.get("executor") != null ? i.get("executor") : "",
                        "executedAt", i.get("executedAt") != null ? i.get("executedAt") : "",
                        "resultDetail", i.get("resultDetail") != null ? i.get("resultDetail") : ""
                ))
                .toList();

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("planId", planId);
        result.put("planName", plan.get("name"));
        result.put("targetVersion", plan.get("target_version"));
        result.put("status", plan.get("status"));
        result.put("total", total);
        result.put("pending", pending);
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("blocked", blocked);
        result.put("skipped", skipped);
        result.put("passRate", total > 0 ? (int) Math.round(passed * 100.0 / total) : 0);
        result.put("failCases", failCases);
        return result;
    }

    /**
     * 推荐 → 执行闭环：把推荐结果落成 DRAFT 测试计划（P0<P1<P2<P3 排序）。
     */
    @Transactional
    public Map<String, Object> createFromRecommendation(Long projectId, String fromVersion,
                                                        String toVersion, String planName) {
        Map<String, Object> rec = recommendationEngine.recommend(projectId, fromVersion, toVersion);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tests = (List<Map<String, Object>>) rec.get("recommendedTests");
        if (tests == null || tests.isEmpty()) {
            throw new IllegalArgumentException("该版本区间无推荐用例，请先补充用例并维护 related_files/related_apis 关联");
        }

        List<Map<String, Object>> ordered = tests.stream()
                .sorted((a, b) -> {
                    int pa = PRIORITY_ORDER.indexOf(a.get("priority"));
                    int pb = PRIORITY_ORDER.indexOf(b.get("priority"));
                    return Integer.compare(pa < 0 ? 99 : pa, pb < 0 ? 99 : pb);
                })
                .toList();

        String name = planName != null && !planName.isBlank() ? planName
                : "回归计划 " + fromVersion + "→" + toVersion;
        Long planId = jdbc.queryForObject("""
                INSERT INTO test_plan(project_id, name, target_version, from_version, status)
                VALUES (?, ?, ?, ?, 'DRAFT') RETURNING id
                """, Long.class, projectId, name, toVersion, fromVersion);

        int order = 0;
        int p0 = 0;
        int p1 = 0;
        for (Map<String, Object> t : ordered) {
            jdbc.update("""
                    INSERT INTO test_plan_item(plan_id, test_case_id, sort_order, status)
                    VALUES (?, ?, ?, 'PENDING') ON CONFLICT (plan_id, test_case_id) DO NOTHING
                    """, planId, t.get("id"), order++);
            if ("P0".equals(t.get("priority"))) p0++;
            if ("P1".equals(t.get("priority"))) p1++;
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("planId", planId);
        result.put("planName", name);
        result.put("itemCount", ordered.size());
        result.put("p0Count", p0);
        result.put("p1Count", p1);
        result.put("totalCount", rec.get("totalCount"));
        result.put("riskLevel", rec.get("riskLevel"));
        result.put("regressionScope", rec.get("regressionScope"));
        return result;
    }

    private String getPlanStatus(Long planId) {
        return jdbc.queryForObject("SELECT status FROM test_plan WHERE id = ?", String.class, planId);
    }
}
