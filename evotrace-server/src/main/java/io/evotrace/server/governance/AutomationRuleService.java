package io.evotrace.server.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 自动化规则引擎（对标 TAPD 自动化助手 / 触发规则）。
 * <p>一组规则挂在事件类型上（triggerEvent），当事件发生时由 {@link #evaluate} 按条件匹配并执行动作。
 * 动作包括：NOTIFY（写入通知）、CREATE_BUG（自动建缺陷）、AUTO_ASSIGN（自动分配）、AI_ANALYZE（AI 分析）。
 * </p>
 */
@Service
public class AutomationRuleService {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleService.class);

    private final JdbcTemplate jdbc;

    public AutomationRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 项目下规则列表。 */
    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, name, trigger_event AS "triggerEvent", action,
                       condition_json AS "condition", config_json AS "config",
                       enabled, run_count AS "runCount", last_run_at AS "lastRunAt",
                       created_at AS "createdAt"
                FROM automation_rule
                WHERE project_id = ? ORDER BY id
                """, projectId);
    }

    /** 创建/更新规则。 */
    @Transactional
    public Map<String, Object> upsert(Long projectId, Map<String, Object> data) {
        Long id = data.get("id") != null ? ((Number) data.get("id")).longValue() : null;
        String name = str(data.get("name"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缺少规则名称");
        }
        String trigger = str(data.get("triggerEvent"));
        String action = str(data.get("action"));
        if (trigger == null || action == null) {
            throw new IllegalArgumentException("缺少触发事件或动作");
        }
        boolean enabled = data.get("enabled") == null || Boolean.TRUE.equals(data.get("enabled"));
        String condition = json(data.get("condition"), "{}");
        String config = json(data.get("config"), "{}");

        if (id == null) {
            Long newId = jdbc.queryForObject("""
                    INSERT INTO automation_rule(project_id, name, trigger_event, action,
                        condition_json, config_json, enabled)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) RETURNING id
                    """, Long.class, projectId, name, trigger, action, condition, config, enabled);
            return Map.of("success", true, "id", newId);
        }
        jdbc.update("""
                UPDATE automation_rule SET name=?, trigger_event=?, action=?, condition_json=?::jsonb,
                    config_json=?::jsonb, enabled=?
                WHERE id=? AND project_id=?
                """, name, trigger, action, condition, config, enabled, id, projectId);
        return Map.of("success", true, "id", id);
    }

    @Transactional
    public void delete(Long projectId, Long ruleId) {
        jdbc.update("DELETE FROM automation_rule WHERE id = ? AND project_id = ?", ruleId, projectId);
    }

    /** 手动触发某事件类型，返回命中的规则数与执行结果。 */
    public Map<String, Object> evaluate(Long projectId, String triggerEvent, Map<String, Object> payload) {
        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT * FROM automation_rule
                WHERE project_id = ? AND enabled = true AND trigger_event = ?
                """, projectId, triggerEvent);
        int matched = 0;
        int executed = 0;
        for (Map<String, Object> rule : rules) {
            Long ruleId = ((Number) rule.get("id")).longValue();
            boolean ok;
            try {
                ok = match(rule, payload);
                if (ok) {
                    execute(rule, payload);
                    executed++;
                }
            } catch (Exception e) {
                log.warn("automation rule {} failed: {}", ruleId, e.getMessage());
                ok = false;
            }
            matched += ok ? 1 : 0;
            recordLog(ruleId, triggerEvent, ok, payload);
        }
        return Map.of("matched", matched, "executed", executed, "totalRules", rules.size());
    }

    /** 事件触发入口（供 ChangeEventHandler / 其他模块调用）。 */
    public void onEvent(Long projectId, String triggerEvent, Map<String, Object> payload) {
        try {
            evaluate(projectId, triggerEvent, payload);
        } catch (Exception e) {
            log.warn("automation rule run skipped: {}", e.getMessage());
        }
    }

    private boolean match(Map<String, Object> rule, Map<String, Object> payload) {
        // 简化条件匹配：condition_json 支持 {severity}, {status}, {branch}, {filePattern}
        Object condObj = rule.get("condition_json");
        if (condObj == null) {
            return true;
        }
        Map<String, Object> cond = asMap(condObj);
        if (cond.isEmpty()) {
            return true;
        }
        if (cond.get("severity") != null && payload.get("severity") != null) {
            if (!cond.get("severity").toString().equalsIgnoreCase(payload.get("severity").toString())) {
                return false;
            }
        }
        if (cond.get("status") != null && payload.get("status") != null) {
            return cond.get("status").toString().equalsIgnoreCase(payload.get("status").toString());
        }
        if (cond.get("branch") != null && payload.get("branch") != null) {
            if (!cond.get("branch").toString().equals(payload.get("branch").toString())) {
                return false;
            }
        }
        return true;
    }

    private void execute(Map<String, Object> rule, Map<String, Object> payload) {
        Long ruleId = ((Number) rule.get("id")).longValue();
        String action = (String) rule.get("action");
        Long projectId = ((Number) rule.get("project_id")).longValue();
        Map<String, Object> config = asMap(rule.get("config_json"));

        switch (action == null ? "" : action) {
            case "NOTIFY" -> {
                String role = str(config.get("targetRole")) == null ? "ALL" : config.get("targetRole").toString();
                String title = "自动化通知: " + str(config.get("title"));
                String content = str(config.get("content"));
                jdbc.update("""
                        INSERT INTO pm_qa_notification(project_id, trigger_event, target_role, title, content)
                        VALUES (?, 'AUTOMATION', ?, ?, ?)
                        """, projectId, role, title != null ? title : "规则触发", content);
            }
            case "CREATE_BUG" -> {
                String title = str(config.get("title"));
                if (title == null || title.isBlank()) {
                    title = "自动化缺陷: " + str(payload.get("eventId"));
                }
                String severity = str(config.get("severity")) != null ? config.get("severity").toString() : "P2";
                jdbc.update("""
                        INSERT INTO bug_ticket(project_id, title, description, severity, status,
                            found_by, source)
                        VALUES (?, ?, ?, ?, 'OPEN', 'AUTO', 'AUTOMATION')
                        """, projectId, title, str(config.get("description")), severity);
            }
            case "AUTO_ASSIGN" -> {
                String assignee = str(config.get("assignee"));
                if (assignee != null && payload.get("bugId") != null) {
                    jdbc.update("UPDATE bug_ticket SET assigned_to = ?, updated_at = now() WHERE id = ?",
                            assignee, ((Number) payload.get("bugId")).longValue());
                }
            }
            case "AI_ANALYZE" -> {
                // 仅记录一次触发（AI 分析由上层按需调用，这里打点）
                log.info("rule {} requested AI analyze for event {}", ruleId, payload.get("eventId"));
            }
            default -> log.debug("unknown automation action: {}", action);
        }

        jdbc.update("UPDATE automation_rule SET run_count = run_count + 1, last_run_at = now() WHERE id = ?", ruleId);
    }

    private void recordLog(Long ruleId, String triggerEvent, boolean matched, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO automation_rule_log(rule_id, trigger_event, matched, result_json)
                VALUES (?, ?, ?, ?::jsonb)
                """, ruleId, triggerEvent, matched,
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(payload).toString());
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String json(Object v, String def) {
        return v != null ? v.toString() : def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
    }
}