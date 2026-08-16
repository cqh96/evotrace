package io.evotrace.server.api;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 性能测试（对标 MeterSphere 性能测试的单机轻量形态）REST 入口。
 * 对指定接口按并发/时长压测，输出 TPS / 平均RT / P95 / 错误率。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/perf")
public class PerformanceTestController {

    private final JdbcTemplate jdbc;
    private final PerformanceTestService perfService;

    public PerformanceTestController(JdbcTemplate jdbc, PerformanceTestService perfService) {
        this.jdbc = jdbc;
        this.perfService = perfService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(perfService.list(projectId(projectKey)));
    }

    public record CreateBody(Long endpointId, String name, Integer concurrency, Integer durationSec, String baseUrl) {}

    @PostMapping
    public Result<Map<String, Object>> create(@PathVariable String projectKey, @RequestBody CreateBody body) {
        Long id = perfService.create(projectId(projectKey), body.endpointId(), body.name(),
                body.concurrency() != null ? body.concurrency() : 10,
                body.durationSec() != null ? body.durationSec() : 30,
                body.baseUrl());
        return Result.ok(Map.of("id", id));
    }

    public record RunBody(String baseUrl) {}

    @PostMapping("/{testId}/run")
    public Result<Map<String, Object>> run(@PathVariable String projectKey, @PathVariable Long testId,
                                           @RequestBody(required = false) RunBody body) {
        String baseUrl = body != null ? body.baseUrl() : null;
        return Result.ok(perfService.run(projectId(projectKey), testId, baseUrl));
    }

    @DeleteMapping("/{testId}")
    public Result<Void> delete(@PathVariable String projectKey, @PathVariable Long testId) {
        perfService.delete(projectId(projectKey), testId);
        return Result.ok(null);
    }
}