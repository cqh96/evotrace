package io.evotrace.server.ai;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI Code Review API — 代码审查报告查询、触发审查、审查历史、AI提交统计。
 */
@RestController
@RequestMapping("/api/v1")
public class CodeReviewController {

    private final JdbcTemplate jdbc;
    private final CodeReviewEngine reviewEngine;

    public CodeReviewController(JdbcTemplate jdbc, CodeReviewEngine reviewEngine) {
        this.jdbc = jdbc;
        this.reviewEngine = reviewEngine;
    }

    /** 触发代码审查（对指定 change event） */
    @PostMapping("/projects/{projectKey}/code-review/{eventId}")
    public Result<Map<String, Object>> review(@PathVariable String projectKey,
                                               @PathVariable String eventId) {
        return Result.ok(reviewEngine.review(eventId));
    }

    /** 获取审查报告 */
    @GetMapping("/projects/{projectKey}/code-review/{eventId}")
    public Result<Map<String, Object>> getReport(@PathVariable String projectKey,
                                                  @PathVariable String eventId) {
        return Result.ok(reviewEngine.getReviewReport(eventId));
    }

    /** 审查历史 */
    @GetMapping("/projects/{projectKey}/code-reviews")
    public Result<List<Map<String, Object>>> listReviews(@PathVariable String projectKey,
                                                           @RequestParam(defaultValue = "30") int limit) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(reviewEngine.listReviews(projectId, limit));
    }

    /** AI 提交统计 */
    @GetMapping("/projects/{projectKey}/code-reviews/stats")
    public Result<List<Map<String, Object>>> stats(@PathVariable String projectKey,
                                                     @RequestParam(defaultValue = "30") int days) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        return Result.ok(reviewEngine.getStats(projectId, days));
    }

    /** AI 提交总览 */
    @GetMapping("/projects/{projectKey}/code-reviews/overview")
    public Result<Map<String, Object>> overview(@PathVariable String projectKey) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);

        int totalReviews = jdbc.queryForObject(
                "SELECT count(*) FROM ai_code_review WHERE project_id = ?", Integer.class, projectId);
        int aiGenerated = jdbc.queryForObject(
                "SELECT count(*) FROM ai_code_review WHERE project_id = ? AND ai_generated = true",
                Integer.class, projectId);
        int passCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_code_review WHERE project_id = ? AND overall_verdict = 'PASS'",
                Integer.class, projectId);
        int failCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_code_review WHERE project_id = ? AND overall_verdict = 'FAIL'",
                Integer.class, projectId);
        Double avgScore = jdbc.queryForObject(
                "SELECT avg(overall_score) FROM ai_code_review WHERE project_id = ?",
                Double.class, projectId);

        // Critical findings count
        int criticalFindings = jdbc.queryForObject("""
                SELECT count(*) FROM review_finding f
                JOIN ai_code_review r ON r.id = f.review_id
                WHERE r.project_id = ? AND f.severity = 'CRITICAL'
                """, Integer.class, projectId);

        return Result.ok(Map.of(
                "totalReviews", totalReviews,
                "aiGenerated", aiGenerated,
                "aiRatio", totalReviews > 0 ? (aiGenerated * 100 / totalReviews) : 0,
                "passCount", passCount,
                "failCount", failCount,
                "avgScore", avgScore != null ? Math.round(avgScore * 10.0) / 10.0 : 0,
                "criticalFindings", criticalFindings
        ));
    }

    /** 确认某个 finding */
    @PostMapping("/code-review/findings/{findingId}/acknowledge")
    public Result<Void> acknowledgeFinding(@PathVariable Long findingId) {
        jdbc.update("UPDATE review_finding SET acknowledged = true WHERE id = ?", findingId);
        return Result.ok(null);
    }

    /** 批量触发审查（对所有待审查的变更事件） */
    @PostMapping("/projects/{projectKey}/code-review/batch")
    public Result<Map<String, Object>> batchReview(@PathVariable String projectKey,
                                                     @RequestParam(defaultValue = "20") int limit) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);

        List<String> eventIds = jdbc.queryForList("""
                SELECT c.event_id FROM change_event c
                WHERE c.project_id = ?
                  AND c.event_id NOT IN (SELECT change_event_id FROM ai_code_review)
                ORDER BY c.occurred_at DESC LIMIT ?
                """, String.class, projectId, limit);

        int reviewed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (String eid : eventIds) {
            try {
                Map<String, Object> result = reviewEngine.review(eid, "AUTO");
                if (result.containsKey("error")) {
                    failures.add(Map.of("eventId", eid, "error", result.get("error")));
                } else {
                    reviewed++;
                }
            } catch (Exception e) {
                failures.add(Map.of("eventId", eid, "error", e.getMessage()));
            }
        }

        return Result.ok(Map.of("total", eventIds.size(), "reviewed", reviewed, "failures", failures));
    }
}
