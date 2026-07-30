package io.evotrace.server.testing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Intelligent test case recommendation engine for QA.
 * Given a set of changed files/APIs, recommends which test cases to run
 * and generates a regression test scope.
 */
@Component
public class TestRecommendationEngine {

    private static final Logger log = LoggerFactory.getLogger(TestRecommendationEngine.class);
    private final JdbcTemplate jdbc;

    public TestRecommendationEngine(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Recommend test cases based on changed files between two versions.
     */
    public Map<String, Object> recommend(Long projectId, String fromVersion, String toVersion) {
        // 1. Get changed files between versions
        List<String> changedFiles = jdbc.queryForList("""
                SELECT DISTINCT f.file_path
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                JOIN release rel_from ON rel_from.project_id = c.project_id AND rel_from.version = ?
                JOIN release rel_to ON rel_to.project_id = c.project_id AND rel_to.version = ?
                WHERE c.project_id = ? AND c.occurred_at > rel_from.released_at
                  AND c.occurred_at <= rel_to.released_at
                """, fromVersion, toVersion, projectId)
                .stream().map(m -> (String) m.get("file_path")).toList();

        if (changedFiles.isEmpty()) {
            return Map.of("changedFiles", List.of(), "recommendedTests", List.of(),
                    "regressionScope", "无变更文件，建议冒烟测试", "riskLevel", "LOW");
        }

        // 2. Match test cases by related_files pattern
        List<Map<String, Object>> matchedTests = new ArrayList<>();
        for (String file : changedFiles) {
            List<Map<String, Object>> tests = jdbc.queryForList("""
                    SELECT tc.* FROM test_case tc
                    WHERE tc.project_id = ? AND tc.related_files ILIKE ?
                    """, projectId, "%" + extractFileName(file) + "%");
            matchedTests.addAll(tests);
        }

        // Deduplicate by ID
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> uniqueTests = matchedTests.stream()
                .filter(t -> seen.add(((Number) t.get("id")).longValue()))
                .collect(Collectors.toList());

        // 3. Also find tests linked to affected APIs
        List<String> changedApis = jdbc.queryForList("""
                SELECT si.identity_key FROM snapshot_item si
                JOIN snapshot_item_ref r ON r.item_hash = si.content_hash
                JOIN snapshot s ON s.id = r.snapshot_id
                JOIN release rel ON rel.id = s.release_id
                WHERE rel.project_id = ? AND rel.version = ? AND si.category = 'API'
                  AND r.change_flag IN ('ADDED','MODIFIED','REMOVED')
                """, projectId, toVersion)
                .stream().map(m -> (String) m.get("identity_key")).toList();

        for (String api : changedApis) {
            List<Map<String, Object>> apiTests = jdbc.queryForList("""
                    SELECT tc.* FROM test_case tc
                    WHERE tc.project_id = ? AND tc.related_apis ILIKE ?
                    """, projectId, "%" + api + "%");
            for (var t : apiTests) {
                if (seen.add(((Number) t.get("id")).longValue())) {
                    uniqueTests.add(t);
                }
            }
        }

        // 4. Prioritize by test priority (P0 first) and separate by type
        List<Map<String, Object>> p0Tests = uniqueTests.stream()
                .filter(t -> "P0".equals(t.get("priority"))).toList();
        List<Map<String, Object>> p1Tests = uniqueTests.stream()
                .filter(t -> "P1".equals(t.get("priority"))).toList();
        List<Map<String, Object>> regressionTests = uniqueTests.stream()
                .filter(t -> "REGRESSION".equals(t.get("test_type"))).toList();

        // 5. Build regression scope
        int totalTests = uniqueTests.size();
        String riskLevel = totalTests > 30 ? "HIGH" : totalTests > 10 ? "MEDIUM" : "LOW";
        String regressionScope = String.format(
                "建议执行 %d 个用例（%d个P0/%d个P1/%d个回归），覆盖 %d 个变更文件",
                totalTests, p0Tests.size(), p1Tests.size(), regressionTests.size(), changedFiles.size());

        log.info("test recommendation: {} files → {} test cases (risk={})",
                changedFiles.size(), totalTests, riskLevel);

        return Map.of(
                "changedFiles", changedFiles,
                "changedApis", changedApis,
                "recommendedTests", uniqueTests,
                "p0Count", p0Tests.size(),
                "p1Count", p1Tests.size(),
                "regressionCount", regressionTests.size(),
                "totalCount", totalTests,
                "regressionScope", regressionScope,
                "riskLevel", riskLevel
        );
    }

    /**
     * Generate regression test scope for a QA pre-release check.
     */
    public Map<String, Object> preReleaseCheck(Long projectId, String targetVersion) {
        // Find the prior version
        List<Map<String, Object>> releases = jdbc.queryForList(
                "SELECT version FROM release WHERE project_id = ? ORDER BY released_at DESC LIMIT 2",
                projectId);
        String fromVersion = releases.size() >= 2 ? (String) releases.get(1).get("version") : null;

        if (fromVersion == null) {
            return Map.of("ready", false, "reason", "无基线版本，建议全量测试");
        }

        Map<String, Object> rec = recommend(projectId, fromVersion, targetVersion);

        // Check quality gate conditions
        int openBugs = jdbc.queryForObject("""
                SELECT count(*) FROM bug_ticket
                WHERE project_id = ? AND severity IN ('P0','P1') AND status NOT IN ('CLOSED','VERIFIED')
                """, Integer.class, projectId);

        int failedTests = jdbc.queryForObject("""
                SELECT count(*) FROM test_execution te
                JOIN test_case tc ON tc.id = te.test_case_id
                WHERE tc.project_id = ? AND te.status = 'FAILED'
                """, Integer.class, projectId);

        boolean ready = openBugs == 0 && failedTests == 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        result.put("openBlockerBugs", openBugs);
        result.put("failedTests", failedTests);
        result.put("recommendedTestCount", rec.get("totalCount"));
        result.put("regressionScope", rec.get("regressionScope"));
        result.put("riskLevel", rec.get("riskLevel"));
        result.put("verdict", ready ? "✅ 质量门禁通过，可以发布" : "❌ 质量门禁未通过: P0/P1缺陷=" + openBugs + " 失败用例=" + failedTests);
        return result;
    }

    private String extractFileName(String path) {
        if (path == null) return "";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
