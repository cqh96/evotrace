package io.evotrace.server.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.common.Result;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求全景 / 版本全景（docs/10 §8.4.4 / §8.4.5，P0-3 用 releaseId 定位）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/trace/overview")
public class TraceOverviewController {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final CompletenessScorer scorer;
    private final TraceRebuildService rebuildService;

    public TraceOverviewController(JdbcTemplate jdbc, CompletenessScorer scorer,
                                   TraceRebuildService rebuildService) {
        this.jdbc = jdbc;
        this.scorer = scorer;
        this.rebuildService = rebuildService;
    }

    private Long pid(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping("/requirement/{requirementId}")
    public Result<Map<String, Object>> requirement(@PathVariable String projectKey,
                                                   @PathVariable Long requirementId) {
        Long p = pid(projectKey);
        Map<String, Object> req = jdbc.queryForMap("""
                SELECT id, req_key AS "reqKey", title, status, priority,
                       target_version AS "targetVersion", iteration_id AS "iterationId"
                FROM requirement WHERE id = ? AND project_id = ?
                """, requirementId, p);

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("changes", jdbc.queryForList("""
                SELECT al.id AS "linkId", c.event_id AS "eventId", c.commit_sha AS "commitSha",
                       c.commit_message AS message, al.confidence, al.source
                FROM artifact_link al JOIN change_event c ON c.event_id = al.from_id
                WHERE al.project_id = ? AND al.from_type = 'CHANGE_EVENT'
                  AND al.to_type = 'REQUIREMENT' AND al.to_id = ?
                  AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                ORDER BY c.occurred_at
                """, p, String.valueOf(requirementId)));
        links.put("testCases", jdbc.queryForList(
                "SELECT id, title, priority FROM test_case WHERE requirement_id = ? ORDER BY priority", requirementId));
        links.put("bugs", jdbc.queryForList(
                "SELECT id, title, severity, status FROM bug_ticket WHERE requirement_id = ? ORDER BY severity, status", requirementId));
        links.put("releases", jdbc.queryForList("""
                SELECT al.id AS "linkId", rel.id, rel.version, al.link_type AS "linkType"
                FROM artifact_link al JOIN release rel ON rel.id = al.to_id::bigint
                WHERE al.project_id = ? AND al.from_type = 'REQUIREMENT' AND al.from_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                """, p, String.valueOf(requirementId)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requirement", req);
        result.put("completeness", scorer.scoreRequirement(p, requirementId));
        result.put("links", links);
        result.put("tasks", List.of()); // A 期空
        result.put("tracePath", tracePath(p, requirementId));
        return Result.ok(result);
    }

    @GetMapping("/release/{releaseId}")
    public Result<Map<String, Object>> release(@PathVariable String projectKey,
                                               @PathVariable Long releaseId) {
        Long p = pid(projectKey);
        Map<String, Object> rel = jdbc.queryForMap("""
                SELECT id, version, base_commit AS "baseCommit", status, released_at AS "releasedAt"
                FROM release WHERE id = ? AND project_id = ?
                """, releaseId, p);

        List<Map<String, Object>> requirements = jdbc.queryForList("""
                SELECT r.id, r.req_key AS "reqKey", r.title, r.status, 0 AS "completenessScore"
                FROM artifact_link al JOIN requirement r ON r.id = al.from_id::bigint
                WHERE al.project_id = ? AND al.from_type = 'REQUIREMENT'
                  AND al.to_type = 'RELEASE' AND al.to_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                ORDER BY r.id
                """, p, String.valueOf(releaseId));

        List<Map<String, Object>> linkedChanges = jdbc.queryForList("""
                SELECT al.id AS "linkId", c.event_id AS "eventId", c.commit_message AS message,
                       (SELECT string_agg(r.req_key, ',')
                        FROM artifact_link al2 JOIN requirement r ON r.id = al2.to_id::bigint
                        WHERE al2.project_id = c.project_id AND al2.from_type = 'CHANGE_EVENT'
                          AND al2.from_id = c.event_id AND al2.to_type = 'REQUIREMENT'
                          AND al2.link_type = 'IMPLEMENTS' AND al2.status = 'ACTIVE') AS "reqKeys"
                FROM artifact_link al JOIN change_event c ON c.event_id = al.from_id
                WHERE al.project_id = ? AND al.from_type = 'CHANGE_EVENT'
                  AND al.to_type = 'RELEASE' AND al.to_id = ?
                  AND al.link_type = 'SHIPPED_IN' AND al.status = 'ACTIVE'
                ORDER BY c.occurred_at
                """, p, String.valueOf(releaseId));
        int total = rebuildService.changesInWindow(p, releaseId).size();
        int linked = linkedChanges.size();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("total", total);
        changes.put("linked", linked);
        changes.put("unlinked", Math.max(0, total - linked));
        changes.put("items", linkedChanges);

        String version = rel.get("version") != null ? rel.get("version").toString() : null;
        List<Map<String, Object>> bugs = new java.util.ArrayList<>();
        int openP0P1 = 0;
        if (version != null) {
            bugs = jdbc.queryForList("""
                    SELECT id, title, severity, status FROM bug_ticket
                    WHERE project_id = ? AND fixed_version = ? AND severity IN ('P0','P1')
                      AND status NOT IN ('CLOSED','VERIFIED')
                    ORDER BY severity, status
                    """, p, version);
            openP0P1 = bugs.size();
        }
        Map<String, Object> bugsOut = new LinkedHashMap<>();
        bugsOut.put("openP0P1", openP0P1);
        bugsOut.put("items", bugs);

        Map<String, Object> qualityGate = Map.of();
        try {
            Map<String, Object> qg = jdbc.queryForMap("""
                    SELECT status, checked_at AS "checkedAt", check_results AS "checkResults"
                    FROM quality_gate WHERE release_id = ? ORDER BY checked_at DESC NULLS LAST LIMIT 1
                    """, releaseId);
            qualityGate = qg;
        } catch (EmptyResultDataAccessException ignore) {
            // 无门禁记录
        }

        Map<String, Object> testSummary = testSummary(releaseId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("release", rel);
        result.put("completeness", scorer.scoreRelease(p, releaseId));
        result.put("requirements", requirements);
        result.put("changes", changes);
        result.put("bugs", bugsOut);
        result.put("qualityGate", qualityGate);
        result.put("testSummary", testSummary);
        result.put("tracePath", List.of());
        return Result.ok(result);
    }

    @PostMapping("/release/{releaseId}/rebuild-changeset")
    public Result<Map<String, Object>> rebuildChangeset(@PathVariable String projectKey,
                                                        @PathVariable Long releaseId) {
        Long p = pid(projectKey);
        int n = rebuildService.rebuildChangeset(p, releaseId);
        return Result.ok(Map.of("rebuiltChanges", n, "releaseId", releaseId));
    }

    private Map<String, Object> testSummary(Long releaseId) {
        int plans = intOf("SELECT count(DISTINCT test_case_id) FROM test_execution WHERE release_id = ?", releaseId);
        int passed = intOf("SELECT count(*) FROM test_execution WHERE release_id = ? AND status IN ('PASSED')", releaseId);
        int failed = intOf("SELECT count(*) FROM test_execution WHERE release_id = ? AND status IN ('FAILED','BLOCKED')", releaseId);
        int total = intOf("SELECT count(*) FROM test_execution WHERE release_id = ?", releaseId);
        double passRate = total > 0 ? Math.round((double) passed / total * 100.0) / 100.0 : 0.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("planCount", plans);
        summary.put("passRate", passRate);
        summary.put("failed", failed);
        return summary;
    }

    private Object tracePath(Long p, Long requirementId) {
        try {
            String json = jdbc.queryForObject("""
                    SELECT trace_path::text FROM e2e_trace
                    WHERE project_id = ? AND requirement_id = ? ORDER BY id DESC LIMIT 1
                    """, String.class, p, requirementId);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (EmptyResultDataAccessException e) {
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private int intOf(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v != null ? v : 0;
    }
}