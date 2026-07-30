package io.evotrace.server.subscription;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD for subscription rules. Users can subscribe to changes
 * filtered by project, event type, file pattern, or app.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final JdbcTemplate jdbc;

    public SubscriptionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(jdbc.queryForList(
                "SELECT id, name, channel, enabled, created_at AS \"createdAt\" FROM subscription_rule ORDER BY id"));
    }

    public record CreateRequest(String name, Long workspaceId, Long userId,
                                 Map<String, Object> filter, String channel, String webhookUrl) {}

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateRequest req) {
        String filterJson;
        try {
            filterJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(req.filter());
        } catch (Exception e) {
            return Result.fail("EVO-BIZ-400", "filter 参数格式错误");
        }

        jdbc.update("""
                INSERT INTO subscription_rule(workspace_id, user_id, name, filter_json, channel, webhook_url)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, req.workspaceId(), req.userId(), req.name(), filterJson,
                req.channel() != null ? req.channel() : "FEISHU", req.webhookUrl());

        return Result.ok(Map.of("created", true));
    }

    @PutMapping("/{id}")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        jdbc.update("UPDATE subscription_rule SET enabled = ?, updated_at = now() WHERE id = ?", enabled, id);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        jdbc.update("DELETE FROM subscription_rule WHERE id = ?", id);
        return Result.ok(null);
    }

    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(jdbc.queryForList("""
                SELECT l.id, l.channel, l.title, l.status, l.error_msg AS "errorMsg",
                       l.created_at AS "createdAt"
                FROM notification_log l ORDER BY l.created_at DESC LIMIT ?
                """, limit));
    }
}
