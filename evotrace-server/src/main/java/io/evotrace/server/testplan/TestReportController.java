package io.evotrace.server.testplan;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 测试报告（对标 MeterSphere 报告体系）REST 入口：报告列表/详情/生成/分享。
 * 分享用随机 token 免登录只读。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/testplan/reports")
public class TestReportController {

    private final JdbcTemplate jdbc;
    private final TestReportService reportService;

    public TestReportController(JdbcTemplate jdbc, TestReportService reportService) {
        this.jdbc = jdbc;
        this.reportService = reportService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(reportService.list(projectId(projectKey)));
    }

    @GetMapping("/{reportId}")
    public Result<Map<String, Object>> detail(@PathVariable String projectKey, @PathVariable Long reportId) {
        return Result.ok(reportService.detail(projectId(projectKey), reportId));
    }

    /** 由一次计划执行生成报告（幂等：同一计划仅保留最新一条）。 */
    @PostMapping("/plans/{planId}/generate")
    public Result<Map<String, Object>> generate(@PathVariable String projectKey, @PathVariable Long planId) {
        return Result.ok(reportService.generateFromPlan(projectId(projectKey), planId));
    }

    @PostMapping("/{reportId}/refresh-share-token")
    public Result<Map<String, Object>> refreshShareToken(@PathVariable String projectKey,
                                                         @PathVariable Long reportId) {
        String token = reportService.refreshShareToken(projectId(projectKey), reportId);
        return Result.ok(Map.of("shareToken", token));
    }

    @DeleteMapping("/{reportId}")
    public Result<Void> delete(@PathVariable String projectKey, @PathVariable Long reportId) {
        reportService.delete(projectId(projectKey), reportId);
        return Result.ok(null);
    }
}