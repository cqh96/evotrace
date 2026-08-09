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
 * 接口场景编排（对标 MeterSphere 场景自动化）REST 入口。
 * <p>场景包含有序步骤（HTTP/EXTRACT/ASSERT/IF），支持环境注入与变量提取，可独立运行或加入测试计划。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/scenarios")
public class ApiScenarioController {

    private final JdbcTemplate jdbc;
    private final ApiScenarioService scenarioService;

    public ApiScenarioController(JdbcTemplate jdbc, ApiScenarioService scenarioService) {
        this.jdbc = jdbc;
        this.scenarioService = scenarioService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    public record ScenarioBody(String name, String description, Boolean enabled,
                               List<Map<String, Object>> steps) {}

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(scenarioService.list(projectId(projectKey)));
    }

    @GetMapping("/{scenarioId}")
    public Result<Map<String, Object>> detail(@PathVariable String projectKey, @PathVariable Long scenarioId) {
        return Result.ok(scenarioService.detail(projectId(projectKey), scenarioId));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@PathVariable String projectKey, @RequestBody ScenarioBody body) {
        Long id = scenarioService.create(projectId(projectKey), body.name(), body.description(), body.steps());
        return Result.ok(Map.of("id", id));
    }

    @PutMapping("/{scenarioId}")
    public Result<Void> update(@PathVariable String projectKey, @PathVariable Long scenarioId,
                               @RequestBody ScenarioBody body) {
        scenarioService.update(projectId(projectKey), scenarioId, body.name(), body.description(),
                body.enabled(), body.steps());
        return Result.ok(null);
    }

    @DeleteMapping("/{scenarioId}")
    public Result<Void> delete(@PathVariable String projectKey, @PathVariable Long scenarioId) {
        scenarioService.delete(projectId(projectKey), scenarioId);
        return Result.ok(null);
    }

    public record RunBody(Long environmentId, Map<String, Object> overrides) {}

    @PostMapping("/{scenarioId}/run")
    public Result<Map<String, Object>> run(@PathVariable String projectKey, @PathVariable Long scenarioId,
                                           @RequestBody(required = false) RunBody body) {
        Long envId = body != null ? body.environmentId() : null;
        Map<String, Object> overrides = body != null ? body.overrides() : null;
        return Result.ok(scenarioService.run(projectId(projectKey), scenarioId, envId, overrides));
    }
}