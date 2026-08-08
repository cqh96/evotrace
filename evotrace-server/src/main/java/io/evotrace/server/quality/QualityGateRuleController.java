package io.evotrace.server.quality;

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
 * 质量门禁规则管理 API（借鉴 SonarQube Quality Gate 可配置化）。
 * 沿用项目惯例：@RequestParam projectKey + JdbcTemplate 查 projectId。
 */
@RestController
@RequestMapping("/api/v1/quality-gate/rules")
public class QualityGateRuleController {

    private final JdbcTemplate jdbc;
    private final QualityGateRuleService ruleService;

    public QualityGateRuleController(JdbcTemplate jdbc, QualityGateRuleService ruleService) {
        this.jdbc = jdbc;
        this.ruleService = ruleService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 生效规则（含全局默认 + 项目覆盖）。 */
    @GetMapping
    public Result<List<Map<String, Object>>> effective(@RequestParam String projectKey) {
        return Result.ok(ruleService.listEffective(projectId(projectKey)));
    }

    /** 项目级规则（仅该项目覆盖项）。 */
    @GetMapping("/project")
    public Result<List<Map<String, Object>>> projectRules(@RequestParam String projectKey) {
        return Result.ok(ruleService.listProject(projectId(projectKey)));
    }

    /** 新增/更新项目级规则（覆盖同名全局规则）。 */
    @PostMapping
    public Result<Map<String, Object>> upsert(@RequestParam String projectKey,
                                              @RequestBody Map<String, Object> body) {
        return Result.ok(ruleService.upsert(projectId(projectKey), body));
    }

    /** 删除项目级规则（恢复全局默认）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestParam String projectKey, @PathVariable Long id) {
        ruleService.delete(projectId(projectKey), id);
        return Result.ok(null);
    }
}