package io.evotrace.server.quality;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量门禁规则管理（借鉴 SonarQube Quality Gate 可配置化）。
 * <p>规则可按项目覆盖全局默认（project_id 为 NULL 表示全局）。提供 CRUD 与按项目聚合查询。</p>
 */
@Service
public class QualityGateRuleService {

    private final JdbcTemplate jdbc;

    public QualityGateRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按项目聚合规则：项目级覆盖同名全局规则，返回生效规则列表。 */
    public List<Map<String, Object>> listEffective(Long projectId) {
        List<Map<String, Object>> global = jdbc.queryForList("""
                SELECT id, rule_key AS "ruleKey", name, description, enabled, weight,
                       threshold, params
                FROM quality_gate_rule WHERE project_id IS NULL ORDER BY id
                """);
        List<Map<String, Object>> projectRules = jdbc.queryForList("""
                SELECT id, rule_key AS "ruleKey", name, description, enabled, weight,
                       threshold, params
                FROM quality_gate_rule WHERE project_id = ? ORDER BY id
                """, projectId);

        Map<String, Map<String, Object>> byKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : global) {
            r.put("scope", "GLOBAL");
            r.put("overridden", false);
            byKey.put((String) r.get("ruleKey"), r);
        }
        for (Map<String, Object> r : projectRules) {
            r.put("scope", "PROJECT");
            r.put("overridden", true);
            byKey.put((String) r.get("ruleKey"), r);
        }
        return new ArrayList<>(byKey.values());
    }

    /** 项目级规则列表（用于编辑）。 */
    public List<Map<String, Object>> listProject(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, rule_key AS "ruleKey", name, description, enabled, weight,
                       threshold, params
                FROM quality_gate_rule WHERE project_id = ? ORDER BY id
                """, projectId);
    }

    /** 创建项目级规则（覆盖同名全局规则）；rule_key 冲突时更新。 */
    @Transactional
    public Map<String, Object> upsert(Long projectId, Map<String, Object> data) {
        String ruleKey = str(data.get("ruleKey"));
        if (ruleKey == null || ruleKey.isBlank()) {
            throw new IllegalArgumentException("缺少 ruleKey");
        }
        String name = data.get("name") != null ? data.get("name").toString() : ruleKey;
        String description = data.get("description") != null ? data.get("description").toString() : null;
        boolean enabled = data.get("enabled") == null || Boolean.TRUE.equals(data.get("enabled"));
        int weight = data.get("weight") != null ? ((Number) data.get("weight")).intValue() : 10;
        String threshold = json(data.get("threshold"), "{}");
        String params = json(data.get("params"), "{}");

        jdbc.update("""
                INSERT INTO quality_gate_rule(project_id, rule_key, name, description, enabled, weight, threshold, params)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (project_id, rule_key) DO UPDATE SET
                    name = EXCLUDED.name,
                    description = EXCLUDED.description,
                    enabled = EXCLUDED.enabled,
                    weight = EXCLUDED.weight,
                    threshold = EXCLUDED.threshold,
                    params = EXCLUDED.params,
                    updated_at = now()
                """, projectId, ruleKey, name, description, enabled, weight, threshold, params);
        return Map.of("ruleKey", ruleKey, "scope", "PROJECT");
    }

    /** 删除项目级规则（project_id=projectId 且 id=ruleId），恢复使用全局默认。 */
    @Transactional
    public void delete(Long projectId, Long ruleId) {
        jdbc.update("DELETE FROM quality_gate_rule WHERE id = ? AND project_id = ?", ruleId, projectId);
    }

    /** 读取项目已生效规则（QualityGateChecker 使用），返回按 weight 排序的校验器列表。 */
    public List<QualityGateRuleEntry> enabledRules(Long projectId) {
        List<Map<String, Object>> rows = listEffective(projectId);
        List<QualityGateRuleEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (Boolean.FALSE.equals(r.get("enabled"))) {
                continue;
            }
            out.add(new QualityGateRuleEntry(
                    (String) r.get("ruleKey"),
                    (String) r.get("name"),
                    ((Number) r.get("weight")).intValue(),
                    (String) r.get("threshold"),
                    (String) r.get("params")));
        }
        return out;
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String json(Object v, String def) {
        if (v == null) {
            return def;
        }
        return v.toString();
    }

    /** 规则校验参数（从 threshold JSON 解析）。 */
    public record QualityGateRuleEntry(String ruleKey, String name, int weight,
                                       String threshold, String params) {
    }
}