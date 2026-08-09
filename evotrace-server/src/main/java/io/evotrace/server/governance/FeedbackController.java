package io.evotrace.server.governance;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 反馈管理 API（P2）：收集反馈 → AI 分析 → 转需求/缺陷。
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final JdbcTemplate jdbc;
    private final FeedbackService feedbackService;

    public FeedbackController(JdbcTemplate jdbc, FeedbackService feedbackService) {
        this.jdbc = jdbc;
        this.feedbackService = feedbackService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String projectKey,
                                                  @RequestParam(required = false) String status) {
        return Result.ok(feedbackService.list(projectId(projectKey), status));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestParam String projectKey,
                                              @RequestBody Map<String, Object> body) {
        return Result.ok(feedbackService.create(projectId(projectKey),
                body.get("content") != null ? body.get("content").toString() : "",
                body.get("source") != null ? body.get("source").toString() : null,
                body.get("createdBy") != null ? body.get("createdBy").toString() : null));
    }

    @PostMapping("/{id}/analyze")
    public Result<Map<String, Object>> analyze(@RequestParam String projectKey, @PathVariable Long id) {
        return Result.ok(feedbackService.analyze(projectId(projectKey), id));
    }

    @PostMapping("/{id}/convert")
    public Result<Map<String, Object>> convert(@RequestParam String projectKey, @PathVariable Long id,
                                               @RequestBody Map<String, Object> body) {
        return Result.ok(feedbackService.convert(projectId(projectKey), id,
                body.get("type") != null ? body.get("type").toString() : "REQUIREMENT",
                body.get("title") != null ? body.get("title").toString() : null,
                body.get("priority") != null ? body.get("priority").toString() : null,
                body.get("summary") != null ? body.get("summary").toString() : null));
    }

    @PostMapping("/{id}/ignore")
    public Result<Void> ignore(@RequestParam String projectKey, @PathVariable Long id) {
        feedbackService.ignore(projectId(projectKey), id);
        return Result.ok(null);
    }
}