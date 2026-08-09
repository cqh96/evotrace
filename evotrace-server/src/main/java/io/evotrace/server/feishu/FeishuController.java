package io.evotrace.server.feishu;

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
 * Feishu Bitable sync configuration and manual sync trigger.
 * Mirrors the Jira controller; runs side-by-side (not replacing) Jira.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/feishu")
public class FeishuController {

    private final JdbcTemplate jdbc;
    private final FeishuConfigService configService;
    private final FeishuBitableService syncService;

    public FeishuController(JdbcTemplate jdbc, FeishuConfigService configService,
                            FeishuBitableService syncService) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.syncService = syncService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig(@PathVariable String projectKey) {
        Map<String, Object> cfg = configService.getConfig(projectId(projectKey));
        return Result.ok(cfg == null ? Map.of("enabled", false) : cfg);
    }

    @PutMapping("/config")
    public Result<Void> saveConfig(@PathVariable String projectKey, @RequestBody Map<String, Object> body) {
        configService.saveConfig(projectId(projectKey), body);
        return Result.ok(null);
    }

    /** 手动触发拉取（飞书 Bitable → EvoTrace），返回导入的缺陷/用例数。 */
    @PostMapping("/sync")
    public Result<Map<String, Object>> sync(@PathVariable String projectKey) {
        try {
            return Result.ok(syncService.pullAll(projectId(projectKey)));
        } catch (Exception e) {
            return Result.fail("EVO-BIZ-002", "飞书同步失败: " + e.getMessage());
        }
    }
}