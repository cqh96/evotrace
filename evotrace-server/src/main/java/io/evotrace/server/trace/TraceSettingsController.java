package io.evotrace.server.trace;

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
import java.util.regex.Pattern;

/**
 * Trace v2 设置与关联规则管理（docs/10 §8.4.1 / §8.4.2）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/trace")
public class TraceSettingsController {

    private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,15}");

    private final JdbcTemplate jdbc;

    public TraceSettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Long pid(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping("/settings")
    public Result<Map<String, Object>> getSettings(@PathVariable String projectKey) {
        return Result.ok(jdbc.queryForMap("SELECT * FROM project_trace_setting WHERE project_id = ?", pid(projectKey)));
    }

    @PutMapping("/settings")
    public Result<Map<String, Object>> updateSettings(@PathVariable String projectKey,
                                                      @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        String prefix = body.get("reqKeyPrefix") != null ? body.get("reqKeyPrefix").toString() : null;
        if (prefix != null && !PREFIX_PATTERN.matcher(prefix).matches()) {
            return Result.fail("REQ_KEY_INVALID", "reqKeyPrefix 仅允许 [A-Za-z][A-Za-z0-9]{0,15}");
        }
        jdbc.update("""
                UPDATE project_trace_setting
                SET req_key_prefix     = COALESCE(?, req_key_prefix),
                    auto_link_enabled  = COALESCE(?, auto_link_enabled),
                    hash_issue_enabled = COALESCE(?, hash_issue_enabled),
                    ai_suggest_enabled = COALESCE(?, ai_suggest_enabled),
                    updated_at         = now()
                WHERE project_id = ?
                """, prefix, body.get("autoLinkEnabled"), body.get("hashIssueEnabled"),
                body.get("aiSuggestEnabled"), p);
        return Result.ok(jdbc.queryForMap("SELECT * FROM project_trace_setting WHERE project_id = ?", p));
    }

    @GetMapping("/rules")
    public Result<List<Map<String, Object>>> listRules(@PathVariable String projectKey) {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM project_link_rule WHERE project_id = ? ORDER BY priority", pid(projectKey)));
    }

    @PostMapping("/rules")
    public Result<Map<String, Object>> createRule(@PathVariable String projectKey,
                                                  @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        String pattern = body.get("pattern") != null ? body.get("pattern").toString() : null;
        String extractGroup = body.get("extractGroup") != null ? body.get("extractGroup").toString() : "reqKey";
        if (pattern == null || !validPattern(pattern, extractGroup)) {
            return Result.fail("PATTERN_INVALID", "正则无法编译或缺少命名组");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO project_link_rule(project_id, name, enabled, priority, pattern,
                    extract_group, apply_to, link_type, confidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, p, body.getOrDefault("name", "rule"),
                body.getOrDefault("enabled", true), body.getOrDefault("priority", 100),
                pattern, extractGroup, body.getOrDefault("applyTo", "COMMIT_MESSAGE"),
                body.getOrDefault("linkType", "IMPLEMENTS"), body.getOrDefault("confidence", 90));
        return Result.ok(jdbc.queryForMap("SELECT * FROM project_link_rule WHERE id = ? AND project_id = ?", id, p));
    }

    @PutMapping("/rules/{id}")
    public Result<Map<String, Object>> updateRule(@PathVariable String projectKey,
                                                  @PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        String pattern = body.get("pattern") != null ? body.get("pattern").toString() : null;
        String extractGroup = body.get("extractGroup") != null ? body.get("extractGroup").toString()
                : (pattern != null ? "reqKey" : null);
        if (pattern != null && !validPattern(pattern, extractGroup != null ? extractGroup : "reqKey")) {
            return Result.fail("PATTERN_INVALID", "正则无法编译或缺少命名组");
        }
        jdbc.update("""
                UPDATE project_link_rule SET
                    name          = COALESCE(?, name),
                    enabled       = COALESCE(?, enabled),
                    priority      = COALESCE(?, priority),
                    pattern       = COALESCE(?, pattern),
                    extract_group = COALESCE(?, extract_group),
                    apply_to      = COALESCE(?, apply_to),
                    link_type     = COALESCE(?, link_type),
                    confidence    = COALESCE(?, confidence),
                    updated_at    = now()
                WHERE id = ? AND project_id = ?
                """, body.get("name"), body.get("enabled"), body.get("priority"), pattern,
                extractGroup, body.get("applyTo"), body.get("linkType"), body.get("confidence"), id, p);
        return Result.ok(jdbc.queryForMap("SELECT * FROM project_link_rule WHERE id = ? AND project_id = ?", id, p));
    }

    @DeleteMapping("/rules/{id}")
    public Result<Void> deleteRule(@PathVariable String projectKey, @PathVariable Long id) {
        jdbc.update("DELETE FROM project_link_rule WHERE id = ? AND project_id = ?", id, pid(projectKey));
        return Result.ok(null);
    }

    /** 恢复默认种子规则（幂等：已存在的 name+apply_to 组合跳过）。 */
    @PostMapping("/rules/seed-defaults")
    public Result<Void> seedDefaults(@PathVariable String projectKey) {
        Long p = pid(projectKey);
        jdbc.update("""
                INSERT INTO project_link_rule (project_id, name, enabled, priority, pattern,
                    extract_group, apply_to, link_type, confidence)
                SELECT ?, v.name, v.enabled, v.priority, v.pattern, v.extract_group, v.apply_to, v.link_type, v.confidence
                FROM (VALUES
                    ('REQ key',        TRUE, 10, '(?i)\\b(?<reqKey>REQ[-_]?\\d+)\\b',     'reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 95),
                    ('REQ key',        TRUE, 10, '(?i)\\b(?<reqKey>REQ[-_]?\\d+)\\b',     'reqKey', 'MR_TITLE',       'IMPLEMENTS', 95),
                    ('REQ key',        TRUE, 10, '(?i)\\b(?<reqKey>REQ[-_]?\\d+)\\b',     'reqKey', 'BRANCH_NAME',    'IMPLEMENTS', 95),
                    ('JIRA/Issue key', TRUE, 20, '(?i)\\b(?<reqKey>[A-Z][A-Z0-9]+-\\d+)\\b','reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 90),
                    ('JIRA/Issue key', TRUE, 20, '(?i)\\b(?<reqKey>[A-Z][A-Z0-9]+-\\d+)\\b','reqKey', 'MR_TITLE',       'IMPLEMENTS', 90),
                    ('Hash issue',     FALSE,30, '#(?<reqKey>\\d+)\\b',                  'reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 70)
                ) AS v(name, enabled, priority, pattern, extract_group, apply_to, link_type, confidence)
                WHERE NOT EXISTS (
                    SELECT 1 FROM project_link_rule r
                    WHERE r.project_id = ? AND r.name = v.name AND r.apply_to = v.apply_to
                )
                """, p, p);
        return Result.ok(null);
    }

    private static boolean validPattern(String pattern, String extractGroup) {
        try {
            Pattern.compile(pattern);
        } catch (Exception e) {
            return false;
        }
        return pattern.contains("(?<") && pattern.contains("(?<" + extractGroup + ">");
    }
}