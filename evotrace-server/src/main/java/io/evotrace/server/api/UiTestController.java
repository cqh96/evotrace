package io.evotrace.server.api;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * UI 测试控制器（对标 MeterSphere UI 自动化）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/ui-tests")
public class UiTestController {

    private final JdbcTemplate jdbc;
    private final UiTestService uiTestService;

    public UiTestController(JdbcTemplate jdbc, UiTestService uiTestService) {
        this.jdbc = jdbc;
        this.uiTestService = uiTestService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(uiTestService.list(projectId(projectKey)));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable String projectKey, @PathVariable Long id) {
        return Result.ok(uiTestService.detail(projectId(projectKey), id));
    }

    public record Body(String name, String description, String baseUrl,
                       List<Map<String, Object>> steps, String script, Boolean enabled) {}

    @PostMapping
    public Result<Map<String, Object>> create(@PathVariable String projectKey, @RequestBody Body body) {
        Long id = uiTestService.create(projectId(projectKey), body.name(), body.description(),
                body.baseUrl(), body.steps(), body.script());
        return Result.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String projectKey, @PathVariable Long id, @RequestBody Body body) {
        uiTestService.update(projectId(projectKey), id, body.name(), body.description(), body.baseUrl(),
                body.steps(), body.script(), body.enabled());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String projectKey, @PathVariable Long id) {
        uiTestService.delete(projectId(projectKey), id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/run")
    public Result<Map<String, Object>> run(@PathVariable String projectKey, @PathVariable Long id) {
        return Result.ok(uiTestService.run(projectId(projectKey), id));
    }
}