package io.evotrace.server.governance;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 研发效能度量 API（对标 TAPD 研效仪表盘）。
 * 沿用项目惯例：@RequestParam projectKey + JdbcTemplate 查 projectId。
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class DevMetricsController {

    private final JdbcTemplate jdbc;
    private final DevMetricsService metricsService;

    public DevMetricsController(JdbcTemplate jdbc, DevMetricsService metricsService) {
        this.jdbc = jdbc;
        this.metricsService = metricsService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 研效总览卡片。 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam String projectKey) {
        return Result.ok(metricsService.overview(projectId(projectKey)));
    }

    /** 近 N 天趋势。 */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam String projectKey,
                                                   @RequestParam(defaultValue = "30") int days) {
        return Result.ok(metricsService.trend(projectId(projectKey), days));
    }

    /** 缺陷分布（严重×状态）。 */
    @GetMapping("/bugs/distribution")
    public Result<List<Map<String, Object>>> bugDistribution(@RequestParam String projectKey) {
        return Result.ok(metricsService.bugDistribution(projectId(projectKey)));
    }

    /** 需求状态分布 + 驻留时长。 */
    @GetMapping("/requirements/flow")
    public Result<List<Map<String, Object>>> requirementFlow(@RequestParam String projectKey) {
        return Result.ok(metricsService.requirementFlow(projectId(projectKey)));
    }

    /** 快照当前周期指标。 */
    @PostMapping("/snapshot")
    public Result<Map<String, Object>> snapshot(@RequestParam String projectKey) {
        return Result.ok(metricsService.snapshot(projectId(projectKey)));
    }

    /** 历史快照。 */
    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam String projectKey) {
        return Result.ok(metricsService.history(projectId(projectKey)));
    }
}