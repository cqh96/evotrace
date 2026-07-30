package io.evotrace.server.requirement;

import io.evotrace.common.Result;
import io.evotrace.server.quality.QualityGateChecker;
import io.evotrace.server.testing.BugTraceService;
import io.evotrace.server.testing.TestRecommendationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * PM-oriented dashboard API: requirement kanban, quality gate,
 * change impact notifications, and release readiness.
 */
@RestController
@RequestMapping("/api/v1/pm")
public class PMDashboardController {

    private final JdbcTemplate jdbc;
    private final RequirementService requirementService;
    private final QualityGateChecker qualityGateChecker;
    private final BugTraceService bugTraceService;
    private final TestRecommendationEngine testEngine;

    public PMDashboardController(JdbcTemplate jdbc, RequirementService requirementService,
                                  QualityGateChecker qualityGateChecker, BugTraceService bugTraceService,
                                  TestRecommendationEngine testEngine) {
        this.jdbc = jdbc;
        this.requirementService = requirementService;
        this.qualityGateChecker = qualityGateChecker;
        this.bugTraceService = bugTraceService;
        this.testEngine = testEngine;
    }

    /** PM Dashboard overview: requirement stats, bug stats, release readiness */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(@RequestParam String projectKey) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);

        // Requirement stats by status
        List<Map<String, Object>> reqStats = jdbc.queryForList("""
                SELECT status, count(*) AS count FROM requirement
                WHERE project_id = ? GROUP BY status
                """, projectId);

        // Bug stats by severity
        List<Map<String, Object>> bugStats = jdbc.queryForList("""
                SELECT severity, count(*) AS count FROM bug_ticket
                WHERE project_id = ? AND status NOT IN ('CLOSED','VERIFIED')
                GROUP BY severity
                """, projectId);

        // Upcoming release target
        Map<String, Object> nextRelease = null;
        try {
            nextRelease = jdbc.queryForMap("""
                    SELECT version, released_at FROM release
                    WHERE project_id = ? AND released_at > now()
                    ORDER BY released_at LIMIT 1
                    """, projectId);
        } catch (Exception ignored) {}

        // Unread notifications for PM
        List<Map<String, Object>> notifications = jdbc.queryForList("""
                SELECT * FROM pm_qa_notification
                WHERE project_id = ? AND target_role IN ('PM','ALL') AND read = false
                ORDER BY created_at DESC LIMIT 10
                """, projectId);

        // Recent bugs found
        List<Map<String, Object>> recentBugs = jdbc.queryForList("""
                SELECT * FROM bug_ticket WHERE project_id = ?
                ORDER BY created_at DESC LIMIT 10
                """, projectId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("requirementStats", reqStats);
        result.put("bugStats", bugStats);
        result.put("nextRelease", nextRelease != null ? nextRelease : Map.of());
        result.put("notifications", notifications);
        result.put("recentBugs", recentBugs);
        return Result.ok(result);
    }

    /** PM: list requirements with filter */
    @GetMapping("/requirements")
    public Result<List<Map<String, Object>>> requirements(@RequestParam String projectKey,
                                                            @RequestParam(required = false) String status) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(requirementService.list(projectId, status));
    }

    /** PM: create/update requirement */
    @PostMapping("/requirements")
    public Result<Map<String, Object>> upsertRequirement(@RequestParam String projectKey,
                                                           @RequestBody Map<String, Object> data) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(requirementService.upsert(projectId, data));
    }

    /** PM: move requirement to next status */
    @PutMapping("/requirements/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam String status,
                                      @RequestParam(defaultValue = "PM") String actor) {
        requirementService.updateStatus(id, status, actor);
        return Result.ok(null);
    }

    /** QA: get recommended test cases for a version */
    @GetMapping("/test-recommendation")
    public Result<Map<String, Object>> testRecommendation(@RequestParam String projectKey,
                                                            @RequestParam String fromVersion,
                                                            @RequestParam String toVersion) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(testEngine.recommend(projectId, fromVersion, toVersion));
    }

    /** QA: run quality gate check */
    @PostMapping("/quality-gate/check")
    public Result<Map<String, Object>> qualityGateCheck(@RequestParam String projectKey,
                                                          @RequestParam String targetVersion,
                                                          @RequestParam(defaultValue = "PM") String checkedBy) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(qualityGateChecker.check(projectId, targetVersion, checkedBy));
    }

    /** QA: quality gate history */
    @GetMapping("/quality-gate/history")
    public Result<List<Map<String, Object>>> qualityGateHistory(@RequestParam String projectKey) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(qualityGateChecker.history(projectId));
    }

    /** QA: create bug */
    @PostMapping("/bugs")
    public Result<Map<String, Object>> createBug(@RequestParam String projectKey,
                                                   @RequestBody Map<String, Object> data) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(bugTraceService.createWithAutoLink(projectId, data));
    }

    /** QA: list bugs */
    @GetMapping("/bugs")
    public Result<List<Map<String, Object>>> listBugs(@RequestParam String projectKey,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String severity) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(bugTraceService.list(projectId, status, severity));
    }

    /** QA: bug trace */
    @GetMapping("/bugs/{bugId}/trace")
    public Result<Map<String, Object>> bugTrace(@PathVariable Long bugId) {
        return Result.ok(bugTraceService.trace(bugId));
    }

    /** QA: link bug to commit */
    @PostMapping("/bugs/{bugId}/link")
    public Result<Void> linkBugToCommit(@PathVariable Long bugId,
                                         @RequestParam String changeEventId,
                                         @RequestParam(defaultValue = "FIX") String linkType) {
        bugTraceService.link(bugId, changeEventId, linkType);
        return Result.ok(null);
    }

    /** Common: PM/QA notifications */
    @GetMapping("/notifications")
    public Result<List<Map<String, Object>>> notifications(@RequestParam String projectKey,
                                                             @RequestParam(defaultValue = "ALL") String role) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(jdbc.queryForList("""
                SELECT * FROM pm_qa_notification
                WHERE project_id = ? AND (target_role = ? OR target_role = 'ALL')
                ORDER BY created_at DESC LIMIT 30
                """, projectId, role));
    }

    /** Mark notification as read */
    @PutMapping("/notifications/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        jdbc.update("UPDATE pm_qa_notification SET read = true WHERE id = ?", id);
        return Result.ok(null);
    }

    /** Release readiness: QA pre-release check */
    @GetMapping("/release-readiness")
    public Result<Map<String, Object>> releaseReadiness(@RequestParam String projectKey,
                                                          @RequestParam String targetVersion) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(testEngine.preReleaseCheck(projectId, targetVersion));
    }
}
