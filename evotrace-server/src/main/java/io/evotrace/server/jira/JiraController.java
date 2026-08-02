package io.evotrace.server.jira;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Jira sync configuration and manual sync trigger.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/jira")
public class JiraController {

    private final JdbcTemplate jdbc;
    private final JiraConfigService configService;
    private final JiraSyncService syncService;

    public JiraController(JdbcTemplate jdbc, JiraConfigService configService,
                          JiraSyncService syncService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.syncService = syncService;
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig(@PathVariable String projectKey) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        Map<String, Object> cfg = configService.getConfig(projectId);
        return Result.ok(cfg != null ? cfg : Map.of("enabled", false));
    }

    @PutMapping("/config")
    public Result<Void> saveConfig(@PathVariable String projectKey,
                                   @RequestBody Map<String, Object> body) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        configService.saveConfig(projectId, body);
        return Result.ok(null);
    }

    /** 手动触发拉取（Jira → EvoTrace） */
    @PostMapping("/sync")
    public Result<Map<String, Object>> sync(@PathVariable String projectKey) {
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
        int imported;
        try {
            imported = syncService.pull(projectId);
        } catch (Exception e) {
            return Result.fail("EVO-BIZ-001", "Jira 同步失败: " + e.getMessage());
        }
        return Result.ok(Map.of("imported", imported));
    }
}
