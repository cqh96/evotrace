package io.evotrace.server.e2e;

import io.evotrace.common.Result;
import io.evotrace.server.requirement.RequirementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * End-to-end traceability: requirement → code change → test case → bug → release.
 * The single pane of glass for PM and QA to trace any artifact across the full SDLC.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/trace")
public class E2ETraceController {

    private final JdbcTemplate jdbc;
    private final RequirementService requirementService;

    public E2ETraceController(JdbcTemplate jdbc, RequirementService requirementService) {
        this.jdbc = jdbc;
        this.requirementService = requirementService;
    }

    private Long pid(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** Full trace: start from any artifact and walk the entire chain */
    @GetMapping("/requirement/{requirementId}")
    public Result<Map<String, Object>> traceRequirement(@PathVariable String projectKey,
                                                         @PathVariable Long requirementId) {
        return Result.ok(requirementService.trace(pid(projectKey), requirementId));
    }

    /** Trace from a bug backwards: Bug → Fix Commit → Requirement → Test Cases */
    @GetMapping("/bug/{bugId}")
    public Result<Map<String, Object>> traceBug(@PathVariable String projectKey, @PathVariable Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);

        // Walk: Bug → Fix Commit → Changed Files
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(Map.of("type", "BUG", "data", bug));

        List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT bl.link_type, c.event_id, c.commit_sha, c.author, c.occurred_at, c.event_type,
                       s.content AS summary
                FROM bug_change_link bl
                JOIN change_event c ON c.event_id = bl.change_event_id
                LEFT JOIN ai_semantic_unit s ON s.target_type='CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE bl.bug_id = ? ORDER BY c.occurred_at
                """, bugId);

        // Walk: Fix Commit → Requirement
        List<Map<String, Object>> relatedReqs = jdbc.queryForList("""
                SELECT r.* FROM requirement r
                JOIN iteration i ON i.id = r.iteration_id
                JOIN change_event c ON c.iteration_id = i.id
                JOIN bug_change_link bl ON bl.change_event_id = c.event_id
                WHERE bl.bug_id = ?
                """, bugId);

        // Walk: Bug → Affected Test Cases
        List<Map<String, Object>> testCases = jdbc.queryForList("""
                SELECT tc.* FROM test_case tc WHERE tc.requirement_id = ?
                """, bug.get("requirement_id"));

        Map<String, Object> result1 = new LinkedHashMap<>();
        result1.put("bug", bug);
        result1.put("fixChain", chain);
        result1.put("fixCommits", links);
        result1.put("relatedRequirements", relatedReqs);
        result1.put("relatedTestCases", testCases);
        return Result.ok(result1);
    }

    /** Trace from a release: what went into this version? */
    @GetMapping("/release/{version}")
    public Result<Map<String, Object>> traceRelease(@PathVariable String projectKey,
                                                     @PathVariable String version) {
        Long projectId = pid(projectKey);

        // Requirements in this release
        List<Map<String, Object>> reqs = jdbc.queryForList("""
                SELECT DISTINCT r.* FROM requirement r
                JOIN iteration i ON i.id = r.iteration_id
                JOIN change_event c ON c.iteration_id = i.id
                JOIN release rel ON rel.project_id = c.project_id AND rel.version = ?
                WHERE c.occurred_at <= rel.released_at AND r.project_id = ?
                """, version, projectId);

        // Changes in this release
        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT c.event_id, c.event_type, c.commit_sha, c.author, c.occurred_at,
                       s.content AS summary
                FROM change_event c
                JOIN release rel ON rel.project_id = c.project_id AND rel.version = ?
                LEFT JOIN ai_semantic_unit s ON s.target_type='CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE c.project_id = ? AND c.occurred_at <= rel.released_at
                ORDER BY c.occurred_at DESC
                """, version, projectId);

        // Bugs fixed in this release
        List<Map<String, Object>> bugs = jdbc.queryForList("""
                SELECT b.* FROM bug_ticket b WHERE b.project_id = ? AND b.fixed_version = ?
                """, projectId, version);

        // Test executions
        List<Map<String, Object>> tests = jdbc.queryForList("""
                SELECT tc.title, tc.test_type, tc.priority, te.status, te.executor, te.executed_at
                FROM test_execution te
                JOIN test_case tc ON tc.id = te.test_case_id
                JOIN release rel ON rel.id = te.release_id AND rel.version = ?
                WHERE tc.project_id = ?
                """, version, projectId);

        // Quality gate
        Map<String, Object> gate = null;
        try {
            gate = jdbc.queryForMap("""
                    SELECT * FROM quality_gate WHERE project_id = ? AND target_version = ?
                    """, projectId, version);
        } catch (Exception ignored) {}

        Map<String, Object> result2 = new LinkedHashMap<>();
        result2.put("version", version);
        result2.put("requirements", reqs);
        result2.put("changes", changes);
        result2.put("bugs", bugs);
        result2.put("testExecutions", tests);
        result2.put("qualityGate", gate != null ? gate : Map.of());
        result2.put("summary", String.format("版本 %s: %d个需求, %d个变更, %d个缺陷修复, %d个测试用例",
                version, reqs.size(), changes.size(), bugs.size(), tests.size()));
        return Result.ok(result2);
    }

    /** Build and persist an E2E trace record */
    @PostMapping("/build")
    public Result<Map<String, Object>> buildTrace(@PathVariable String projectKey,
                                                    @RequestParam Long requirementId) {
        Long projectId = pid(projectKey);

        List<Map<String, Object>> path = new ArrayList<>();
        // Requirement → Iteration → Changes → TestCases → Bugs → Release
        Map<String, Object> req = jdbc.queryForMap("SELECT * FROM requirement WHERE id = ?", requirementId);
        path.add(Map.of("type", "REQUIREMENT", "id", requirementId, "title", req.get("title")));

        jdbc.queryForList("""
                SELECT DISTINCT c.event_id, c.event_type, c.occurred_at
                FROM change_event c
                JOIN iteration i ON i.id = c.iteration_id AND i.id = ?
                """, ((Number) req.get("iteration_id")).longValue())
                .forEach(c -> path.add(Map.of("type", "CHANGE", "id", c.get("event_id"),
                        "eventType", c.get("event_type"), "time", c.get("occurred_at").toString())));

        jdbc.queryForList("SELECT id, title FROM test_case WHERE requirement_id = ?", requirementId)
                .forEach(tc -> path.add(Map.of("type", "TEST_CASE", "id", tc.get("id"), "title", tc.get("title"))));

        jdbc.queryForList("SELECT id, title, status FROM bug_ticket WHERE requirement_id = ?", requirementId)
                .forEach(b -> path.add(Map.of("type", "BUG", "id", b.get("id"),
                        "title", b.get("title"), "status", b.get("status"))));

        try {
            String pathJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(path);
            jdbc.update("INSERT INTO e2e_trace(project_id, requirement_id, trace_path) VALUES (?, ?, ?::jsonb)",
                    projectId, requirementId, pathJson);
        } catch (Exception e) {
            return Result.fail("EVO-SYS-500", "构建追溯链失败: " + e.getMessage());
        }

        return Result.ok(Map.of("path", path, "nodeCount", path.size()));
    }

    /** Get all E2E traces for a project */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        Long projectId = pid(projectKey);
        return Result.ok(jdbc.queryForList("""
                SELECT et.id, r.title AS "requirementTitle",
                       jsonb_array_length(et.trace_path) AS "nodeCount",
                       et.created_at AS "createdAt"
                FROM e2e_trace et
                JOIN requirement r ON r.id = et.requirement_id
                WHERE et.project_id = ?
                ORDER BY et.created_at DESC LIMIT 20
                """, projectId));
    }
}
