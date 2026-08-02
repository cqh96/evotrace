package io.evotrace.server.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Per-project Jira sync configuration (project_jira_config). The status_map
 * column drives both directions: EvoTrace status → Jira status name (push)
 * and Jira status → EvoTrace status (pull). Same structure can later host
 * TAPD credentials (source column extension).
 */
@Service
public class JiraConfigService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JiraConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns null when the project has no config row (not an error). */
    public Map<String, Object> getConfig(Long projectId) {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, base_url AS "baseUrl", username, api_token AS "apiToken",
                       jira_project_key AS "jiraProjectKey", issue_type AS "issueType",
                       status_map AS "statusMap", enabled, last_sync_at AS "lastSyncAt"
                FROM project_jira_config WHERE project_id = ?
                """, projectId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> cfg = rows.get(0);
        cfg.remove("apiToken"); // write-only: never echo the stored token
        if (cfg.get("statusMap") != null) {
            try {
                cfg.put("statusMap", mapper.readValue(String.valueOf(cfg.get("statusMap")), Map.class));
            } catch (Exception e) {
                cfg.put("statusMap", Map.of());
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
        // apiToken is write-only: keep the existing value when not provided
        String apiToken = body.get("apiToken") != null && !String.valueOf(body.get("apiToken")).isBlank()
                ? String.valueOf(body.get("apiToken")) : null;
        String statusMapJson = "{}";
        if (body.get("statusMap") instanceof Map<?, ?> map && !map.isEmpty()) {
            try {
                statusMapJson = mapper.writeValueAsString(map);
            } catch (Exception ignored) {
            }
        }

        int updated = jdbc.update("""
                UPDATE project_jira_config SET base_url = ?, username = ?,
                    api_token = COALESCE(?, api_token),
                    jira_project_key = ?, issue_type = ?, status_map = ?::jsonb,
                    enabled = ?, updated_at = now()
                WHERE project_id = ?
                """, body.get("baseUrl"), body.get("username"), apiToken,
                body.get("jiraProjectKey"), body.getOrDefault("issueType", "Bug"),
                statusMapJson, Boolean.TRUE.equals(body.get("enabled")), projectId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO project_jira_config(project_id, base_url, username, api_token,
                        jira_project_key, issue_type, status_map, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, projectId, body.get("baseUrl"), body.get("username"), apiToken,
                    body.get("jiraProjectKey"), body.getOrDefault("issueType", "Bug"),
                    statusMapJson, Boolean.TRUE.equals(body.get("enabled")));
        }
    }

    @Transactional
    public void touchSyncTime(Long projectId) {
        jdbc.update("UPDATE project_jira_config SET last_sync_at = now() WHERE project_id = ?", projectId);
    }
}
