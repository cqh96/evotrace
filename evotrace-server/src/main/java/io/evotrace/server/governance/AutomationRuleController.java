package io.evotrace.server.governance;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 自动化规则引擎 API（P0-3）。
 */
@RestController
@RequestMapping("/api/v1/automation-rules")
public class AutomationRuleController {

    private final JdbcTemplate jdbc;
    private final AutomationRuleService ruleService;

    public AutomationRuleController(JdbcTemplate jdbc, AutomationRuleService ruleService) {
        this.jdbc = jdbc;
        this.ruleService = ruleService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam String projectKey) {
        return Result.ok(ruleService.list(projectId(projectKey)));
    }

    @PostMapping
    public Result<Map<String, Object>> upsert(@RequestParam String projectKey,
                                              @RequestBody Map<String, Object> body) {
        return Result.ok(ruleService.upsert(projectId(projectKey), body));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestParam String projectKey, @PathVariable Long id) {
        ruleService.delete(projectId(projectKey), id);
        return Result.ok(null);
    }

    /** 手动触发某事件类型做冒烟验证。 */
    @PostMapping("/trigger")
    public Result<Map<String, Object>> trigger(@RequestParam String projectKey,
                                               @RequestBody Map<String, Object> body) {
        return Result.ok(ruleService.evaluate(projectId(projectKey),
                body.get("triggerEvent") != null ? body.get("triggerEvent").toString() : "",
                body.get("payload") instanceof Map ? asMap(body.get("payload")) : Map.of()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }
}