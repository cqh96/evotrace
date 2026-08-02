package io.evotrace.server.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-release quality gate checker. Runs N checks before allowing a release.
 * PM and QA use this to decide "can we ship?"
 */
@Component
public class QualityGateChecker {

    private static final Logger log = LoggerFactory.getLogger(QualityGateChecker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public QualityGateChecker(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Run all quality gate checks for a release target.
     *
     * @param planId optional: bind the failedTests check to a test plan
     *               (its failed items) instead of the release time window
     */
    public Map<String, Object> check(Long projectId, String targetVersion, String checkedBy,
                                     Long planId) {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean allPassed = true;
        int totalWeight = 100;
        int score = 0;

        // Check 1: Open P0/P1 bugs (30 points)
        int blockerBugs = jdbc.queryForObject("""
                SELECT count(*) FROM bug_ticket WHERE project_id = ?
                AND severity IN ('P0','P1') AND status NOT IN ('CLOSED','VERIFIED')
                """, Integer.class, projectId);
        boolean bugPassed = blockerBugs == 0;
        checks.put("openBlockerBugs", Map.of("passed", bugPassed, "value", blockerBugs, "threshold", 0,
                "weight", 30, "message", bugPassed ? "无 P0/P1 缺陷" : "存在 " + blockerBugs + " 个未关闭的 P0/P1 缺陷"));
        if (bugPassed) score += 30;
        else allPassed = false;

        // Check 2: Failed test cases (25 points).
        // Scope: the given plan's failed items, or the release time window
        // (previous release → target release), falling back to all-time.
        Map<String, Object> failedScope = resolveFailedTests(projectId, targetVersion, planId);
        int failedTests = ((Number) failedScope.get("value")).intValue();
        boolean testPassed = failedTests == 0;
        Map<String, Object> failedChecks = new LinkedHashMap<>();
        failedChecks.put("passed", testPassed);
        failedChecks.put("value", failedTests);
        failedChecks.put("threshold", 0);
        failedChecks.put("weight", 25);
        failedChecks.put("scope", failedScope.get("scope"));
        failedChecks.put("message", testPassed
                ? "所有用例通过（口径: " + failedScope.get("scope") + "）"
                : failedTests + " 个失败用例（口径: " + failedScope.get("scope") + "）");
        checks.put("failedTests", failedChecks);
        if (testPassed) score += 25;
        else allPassed = false;

        // Check 3: Breaking changes (20 points)
        int breakingChanges = jdbc.queryForObject("""
                SELECT count(*) FROM breaking_change_alert
                WHERE project_id = ? AND severity IN ('CRITICAL','WARNING') AND acknowledged = false
                """, Integer.class, projectId);
        boolean breakPassed = breakingChanges == 0;
        checks.put("unacknowledgedBreaks", Map.of("passed", breakPassed, "value", breakingChanges, "threshold", 0,
                "weight", 20, "message", breakPassed ? "无未确认的破坏性变更" : breakingChanges + " 个未确认的破坏性变更"));
        if (breakPassed) score += 20;
        else allPassed = false;

        // Check 4: Test coverage of changed files (15 points)
        int changedFiles = jdbc.queryForObject("""
                SELECT count(DISTINCT f.file_path) FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                WHERE c.project_id = ? AND c.occurred_at >= now() - interval '14 days'
                """, Integer.class, projectId);
        int coveredFiles = jdbc.queryForObject("""
                SELECT count(DISTINCT f.file_path) FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                JOIN test_case tc ON tc.related_files ILIKE '%' || split_part(f.file_path, '/',
                    array_length(string_to_array(f.file_path, '/'), 1)) || '%'
                WHERE c.project_id = ? AND c.occurred_at >= now() - interval '14 days'
                """, Integer.class, projectId);
        int coverage = changedFiles > 0 ? (coveredFiles * 100 / changedFiles) : 100;
        boolean covPassed = coverage >= 60;
        checks.put("testCoverage", Map.of("passed", covPassed, "value", coverage + "%",
                "threshold", "60%", "weight", 15,
                "message", covPassed ? "测试覆盖率 " + coverage + "%" : "测试覆盖率仅 " + coverage + "%，建议补充"));
        if (covPassed) score += 15;
        else allPassed = false;

        // Check 5: Risk score (10 points)
        Integer riskScore = null;
        try {
            riskScore = jdbc.queryForObject("""
                    SELECT total_score FROM release_risk_score rs
                    JOIN release rel ON rel.id = rs.release_id
                    WHERE rel.project_id = ? AND rel.version = ?
                    """, Integer.class, projectId, targetVersion);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
            // no risk score computed for this release yet
        }
        if (riskScore == null) riskScore = 50; // default if not computed
        boolean riskPassed = riskScore <= 60;
        checks.put("riskScore", Map.of("passed", riskPassed, "value", riskScore, "threshold", 60,
                "weight", 10, "message", riskPassed ? "风险评估通过 (" + riskScore + "/100)" : "风险评分过高 (" + riskScore + "/100)"));
        if (riskPassed) score += 10;
        else allPassed = false;

        // Persist
        try {
            jdbc.update("""
                    INSERT INTO quality_gate(project_id, release_id, target_version, status, check_results, checked_at, checked_by)
                    VALUES (?, (SELECT id FROM release WHERE project_id=? AND version=?), ?, ?,
                        ?::jsonb, now(), ?)
                    """, projectId, projectId, targetVersion, targetVersion,
                    allPassed ? "PASSED" : "FAILED",
                    mapper.writeValueAsString(checks), checkedBy);
        } catch (Exception e) {
            log.warn("failed to persist quality gate result", e);
        }

        String verdict = allPassed
                ? "✅ 质量门禁全部通过 (" + score + "/100) —— 可以发布 " + targetVersion
                : "❌ 质量门禁未通过 (" + score + "/100) —— 请修复后重试";

        return Map.of("passed", allPassed, "score", score, "checks", checks, "verdict", verdict,
                "checkedAt", OffsetDateTime.now().toString(), "checkedBy", checkedBy);
    }

    /** pgjdbc returns TIMESTAMPTZ as java.sql.Timestamp (not OffsetDateTime). */
    private static OffsetDateTime toOdt(Object v) {
        if (v instanceof OffsetDateTime odt) return odt;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    /** Failed-test count with an explicit scope: plan / release window / all-time. */
    private Map<String, Object> resolveFailedTests(Long projectId, String targetVersion, Long planId) {
        if (planId != null) {
            int failed = jdbc.queryForObject("""
                    SELECT count(*) FROM test_plan_item pi
                    JOIN test_plan tp ON tp.id = pi.plan_id
                    WHERE tp.project_id = ? AND pi.plan_id = ? AND pi.status = 'FAILED'
                    """, Integer.class, projectId, planId);
            String planName = jdbc.queryForObject(
                    "SELECT name FROM test_plan WHERE id = ?", String.class, planId);
            return Map.of("value", failed, "scope", "PLAN:" + planName);
        }

        // Locate the release time window: (previous release, target release]
        java.util.List<Map<String, Object>> releases = jdbc.queryForList("""
                SELECT version, released_at FROM release
                WHERE project_id = ? ORDER BY released_at
                """, projectId);
        OffsetDateTime upper = null;
        OffsetDateTime lower = null;
        for (int i = 0; i < releases.size(); i++) {
            Map<String, Object> r = releases.get(i);
            if (targetVersion != null && targetVersion.equals(r.get("version"))) {
                upper = toOdt(r.get("released_at"));
                if (i > 0) {
                    lower = toOdt(releases.get(i - 1).get("released_at"));
                }
                break;
            }
        }
        if (upper == null) {
            int allTime = jdbc.queryForObject("""
                    SELECT count(DISTINCT te.test_case_id) FROM test_execution te
                    JOIN test_case tc ON tc.id = te.test_case_id
                    WHERE tc.project_id = ? AND te.status = 'FAILED'
                    """, Integer.class, projectId);
            return Map.of("value", allTime, "scope", "ALL(目标版本未登记 release，按全历史)");
        }
        if (lower == null) {
            // First release of the project: no previous baseline
            int failed = jdbc.queryForObject("""
                    SELECT count(DISTINCT te.test_case_id) FROM test_execution te
                    JOIN test_case tc ON tc.id = te.test_case_id
                    WHERE tc.project_id = ? AND te.status = 'FAILED' AND te.executed_at <= ?
                    """, Integer.class, projectId, upper);
            return Map.of("value", failed, "scope", "RELEASE:≤" + targetVersion);
        }
        int failed = jdbc.queryForObject("""
                SELECT count(DISTINCT te.test_case_id) FROM test_execution te
                JOIN test_case tc ON tc.id = te.test_case_id
                WHERE tc.project_id = ? AND te.status = 'FAILED'
                  AND te.executed_at > ? AND te.executed_at <= ?
                """, Integer.class, projectId, lower, upper);
        return Map.of("value", failed, "scope", "RELEASE:" + lower.toLocalDate() + "→" + upper.toLocalDate());
    }

    /** Get quality gate history */
    public java.util.List<Map<String, Object>> history(Long projectId) {
        return jdbc.queryForList("""
                SELECT qg.target_version AS "targetVersion", qg.status,
                       qg.check_results AS "checkResults", qg.checked_at AS "checkedAt",
                       qg.checked_by AS "checkedBy"
                FROM quality_gate qg
                WHERE qg.project_id = ?
                ORDER BY qg.checked_at DESC LIMIT 20
                """, projectId);
    }
}
