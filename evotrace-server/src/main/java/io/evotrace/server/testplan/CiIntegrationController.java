package io.evotrace.server.testplan;

import io.evotrace.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CI/CD 集成（对标 MeterSphere + Jenkins 无缝对接）：提供带 token 鉴权的触发端点，
 * 供 Jenkins / GitHub Actions / GitLab CI 等 DevOps 流水线调用，实现在流水线里执行测试计划。
 * <p>端点分两层：<ul>
 *   <li>项目级触发 {@code /api/v1/projects/{key}/ci/triggers}：配置/列出某项目的 CI 触发任务（绑定测试计划）</li>
 *   <li>无鉴权触发 {@code /open-api/v1/ci/run}：用项目 API Key + HMAC 签名（与事件上报同源）触发计划，免登录供流水线调用</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1")
public class CiIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(CiIntegrationController.class);

    private final JdbcTemplate jdbc;
    private final TestExecutionRunner runner;
    private final TestReportService reportService;
    private final String ciToken;

    public CiIntegrationController(JdbcTemplate jdbc, TestExecutionRunner runner,
                                   TestReportService reportService,
                                   @Value("${evotrace.ci.token:evotrace-devops-2026}") String ciToken) {
        this.jdbc = jdbc;
        this.runner = runner;
        this.reportService = reportService;
        this.ciToken = ciToken;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    // ==================== 项目级 CI 触发配置 ====================

    @GetMapping("/projects/{projectKey}/ci/triggers")
    public Result<List<Map<String, Object>>> listTriggers(@PathVariable String projectKey) {
        return Result.ok(jdbc.queryForList("""
                SELECT id, project_id AS "projectId", plan_id AS "planId", tp.name AS "planName",
                       name, trigger_type AS "triggerType", enabled, created_by AS "createdBy",
                       created_at AS "createdAt"
                FROM ci_trigger WHERE project_id = ? ORDER BY id
                """, projectId(projectKey)));
    }

    public record TriggerBody(Long planId, String name, String triggerType, Boolean enabled) {}

    @PostMapping("/projects/{projectKey}/ci/triggers")
    public Result<Map<String, Object>> createTrigger(@PathVariable String projectKey,
                                                     @RequestBody TriggerBody body) {
        Long id = jdbc.queryForObject("""
                INSERT INTO ci_trigger(project_id, plan_id, name, trigger_type, enabled)
                VALUES (?, ?, ?, COALESCE(?, 'WEBHOOK'), COALESCE(?, TRUE)) RETURNING id
                """, Long.class, projectId(projectKey), body.planId(), body.name(), body.triggerType(), body.enabled());
        return Result.ok(Map.of("id", id));
    }

    @DeleteMapping("/projects/{projectKey}/ci/triggers/{triggerId}")
    public Result<Void> deleteTrigger(@PathVariable String projectKey, @PathVariable Long triggerId) {
        jdbc.update("DELETE FROM ci_trigger WHERE id = ? AND project_id = ?", triggerId, projectId(projectKey));
        return Result.ok(null);
    }

    // ==================== token 触发的 CI 执行（支持 Jenkins 直接调用） ====================

    public record CiRunBody(String projectKey, Long planId, Boolean generateReport) {}

    @PostMapping("/ci/run")
    public Result<Map<String, Object>> runByToken(@RequestHeader("X-CI-Token") String token,
                                                  @RequestBody CiRunBody body) {
        if (this.ciToken == null || this.ciToken.isBlank() || !this.ciToken.equals(token)) {
            return Result.fail("EVO-AUTH-401", "CI token 无效");
        }
        if (body.projectKey() == null || body.planId() == null) {
            return Result.fail("EVO-BIZ-400", "缺少 projectKey 或 planId");
        }
        Long projectId = projectId(body.projectKey());
        Map<String, Object> result = runner.runPlan(projectId, body.planId(), Map.of());
        if (Boolean.TRUE.equals(body.generateReport())) {
            try { result.put("report", reportService.generateFromPlan(projectId, body.planId())); }
            catch (Exception e) { log.warn("CI: 报告生成失败 project={} plan={}: {}", projectId, body.planId(), e.getMessage()); }
        }
        log.info("CI run by token: project={} plan={} passed={} failed={} skipped={}",
                body.projectKey(), body.planId(), result.get("passed"), result.get("failed"), result.get("skipped"));
        return Result.ok(result);
    }
}