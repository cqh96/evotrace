package io.evotrace.server.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Per-project Feishu Bitable sync configuration (project_feishu_config).
 * <p>
 * Mirrors {@code JiraConfigService}: app_secret is write-only (never echoed),
 * field_map drives Bitable column mapping for both bugs and test cases, and
 * status_map drives EvoTrace status ↔ Bitable status text in both directions.
 * Runs side-by-side with Jira — each project enables independently.
 */
@Service
public class FeishuConfigService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public FeishuConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns null when the project has no config row (not an error). */
    public Map<String, Object> getConfig(Long projectId) {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT project_id AS "projectId", app_id AS "appId", app_token AS "appToken",
                       bug_table_id AS "bugTableId", case_table_id AS "caseTableId",
                       field_map AS "fieldMap", status_map AS "statusMap",
                       enabled, last_sync_at AS "lastSyncAt"
                FROM project_feishu_config WHERE project_id = ?
                """, projectId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> cfg = rows.get(0);
        cfg.remove("appSecret"); // write-only: never echo the stored secret
        for (String key : new String[]{"fieldMap", "statusMap"}) {
            if (cfg.get(key) != null) {
                try {
                    cfg.put(key, mapper.readValue(String.valueOf(cfg.get(key)), Map.class));
                } catch (Exception e) {
                    cfg.put(key, Map.of());
                }
            }
        }
        return cfg;
    }

    public boolean isEnabled(Long projectId) {
        Map<String, Object> cfg = getConfig(projectId);
        return cfg != null && Boolean.TRUE.equals(cfg.get("enabled"));
    }

    @Transactional
    public void saveConfig(Long projectId, Map<String, Object> body) {
        // appSecret is write-only: keep the existing value when not provided
        String appSecret = body.get("appSecret") != null && !String.valueOf(body.get("appSecret")).isBlank()
                ? String.valueOf(body.get("appSecret")) : null;
        String fieldMapJson = json(body.get("fieldMap"));
        String statusMapJson = json(body.get("statusMap"));

        int updated = jdbc.update("""
                UPDATE project_feishu_config SET app_id = ?, app_token = ?,
                    app_secret = COALESCE(?, app_secret),
                    bug_table_id = ?, case_table_id = ?,
                    field_map = ?::jsonb, status_map = ?::jsonb,
                    enabled = ?, updated_at = now()
                WHERE project_id = ?
                """, body.get("appId"), body.get("appToken"), appSecret,
                body.get("bugTableId"), body.get("caseTableId"),
                fieldMapJson, statusMapJson, Boolean.TRUE.equals(body.get("enabled")), projectId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO project_feishu_config(project_id, app_id, app_secret, app_token,
                        bug_table_id, case_table_id, field_map, status_map, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    """, projectId, body.get("appId"), appSecret, body.get("appToken"),
                    body.get("bugTableId"), body.get("caseTableId"),
                    fieldMapJson, statusMapJson, Boolean.TRUE.equals(body.get("enabled")));
        }
    }

    @Transactional
    public void touchSyncTime(Long projectId) {
        jdbc.update("UPDATE project_feishu_config SET last_sync_at = now() WHERE project_id = ?", projectId);
    }

    private static String json(Object o) {
        if (o instanceof Map<?, ?> map && !map.isEmpty()) {
            try {
                return mapper.writeValueAsString(map);
            } catch (Exception ignored) {
            }
        }
        return "{}";
    }
}