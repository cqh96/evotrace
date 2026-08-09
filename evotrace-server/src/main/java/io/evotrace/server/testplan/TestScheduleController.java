package io.evotrace.server.testplan;

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
 * 定时调度（对标 MeterSphere + CI 持续测试）REST 入口。
 * 按 cron 表达式定时触发测试计划执行，融入持续交付。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/testplan/schedules")
public class TestScheduleController {

    private final JdbcTemplate jdbc;
    private final TestScheduleService scheduleService;

    public TestScheduleController(JdbcTemplate jdbc, TestScheduleService scheduleService) {
        this.jdbc = jdbc;
        this.scheduleService = scheduleService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(scheduleService.list(projectId(projectKey)));
    }

    public record ScheduleBody(Long planId, String name, String cron, Boolean enabled) {}

    @PostMapping
    public Result<Map<String, Object>> create(@PathVariable String projectKey, @RequestBody ScheduleBody body) {
        Long id = scheduleService.create(projectId(projectKey), body.planId(), body.name(), body.cron());
        return Result.ok(Map.of("id", id));
    }

    @PutMapping("/{scheduleId}")
    public Result<Void> update(@PathVariable String projectKey, @PathVariable Long scheduleId,
                               @RequestBody ScheduleBody body) {
        scheduleService.update(projectId(projectKey), scheduleId, body.name(), body.cron(), body.enabled());
        return Result.ok(null);
    }

    @DeleteMapping("/{scheduleId}")
    public Result<Void> delete(@PathVariable String projectKey, @PathVariable Long scheduleId) {
        scheduleService.delete(projectId(projectKey), scheduleId);
        return Result.ok(null);
    }
}