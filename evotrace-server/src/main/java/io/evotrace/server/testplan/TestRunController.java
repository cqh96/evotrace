package io.evotrace.server.testplan;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务端执行接口：对测试用例/测试计划中的 API 型（http 步骤）用例执行真实请求与断言。
 * 同步执行；浏览器 UI 步骤用例不可执行（计划运行中标记 SKIPPED 带原因）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/testplan")
public class TestRunController {

    private final JdbcTemplate jdbc;
    private final TestExecutionRunner runner;

    public TestRunController(JdbcTemplate jdbc, TestExecutionRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 执行单个用例（须为 API 型可执行用例），结果写入执行记录。 */
    @PostMapping("/cases/{caseId}/run")
    public Result<Map<String, Object>> runCase(@PathVariable String projectKey,
                                               @PathVariable Long caseId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(runner.runCase(projectId(projectKey), caseId, body));
    }

    /** 执行整个计划：DRAFT 自动转 RUNNING，逐项执行并更新计划项。 */
    @PostMapping("/plans/{planId}/run")
    public Result<Map<String, Object>> runPlan(@PathVariable String projectKey,
                                               @PathVariable Long planId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return Result.ok(runner.runPlan(projectId(projectKey), planId, body));
    }
}
