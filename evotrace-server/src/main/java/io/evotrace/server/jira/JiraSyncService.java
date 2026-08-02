package io.evotrace.server.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Jira ↔ EvoTrace bug bidirectional sync (REST API v2 over java.net.http).
 * <p>
 * - pull(): JQL search for issues updated since last sync → upsert bug_ticket
 *   (source='JIRA', external_key=issue key, status mapped back via status_map).
 * - pushNewBug() / pushStatus(): create issues and drive transitions so local
 *   bug lifecycle changes are reflected in Jira.
 * <p>
 * Every operation degrades gracefully: no config / disabled / unreachable Jira
 * → logged and skipped, never blocking the local pipeline. Scheduled nightly
 * pull across all enabled projects.
 */
@Service
public class JiraSyncService {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final JiraConfigService configService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public JiraSyncService(JdbcTemplate jdbc, JiraConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    @Scheduled(cron = "${evotrace.jira.pull-cron:0 20 0 * * *}")
    public void scheduledPull() {
        List<Long> projectIds = jdbc.queryForList(
                "SELECT project_id FROM project_jira_config WHERE enabled = true", Long.class);
        for (Long projectId : projectIds) {
            try {
                int imported = pull(projectId);
                log.info("jira pull: project={} imported={}", projectId, imported);
            } catch (Exception e) {
                log.warn("jira pull failed for project {}: {}", projectId, e.getMessage());
            }
        }
    }

    /** Pull issues updated since last sync into bug_ticket. Returns imported count. */
    @SuppressWarnings("unchecked")
    public int pull(Long projectId) {
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled"))) {
            return 0;
        }
        Object lastSync = cfg.get("lastSyncAt");
        String jql = "project=" + cfg.get("jiraProjectKey");
        if (lastSync != null) {
            String iso = String.valueOf(lastSync).replace(' ', 'T').substring(0, 19);
            jql += " AND updated>=\"" + iso + "\"";
        }
        Map<String, Object> resp = jiraGet(cfg, "/rest/api/2/search",
                "jql=" + urlEncode(jql) + "&fields=key,summary,description,status,priority,created&maxResults=100");
        if (resp == null) {
            return 0;
        }
        List<Map<String, Object>> issues = (List<Map<String, Object>>) resp.getOrDefault("issues", List.of());
        int imported = 0;
        for (Map<String, Object> issue : issues) {
            imported += upsertIssue(projectId, issue, cfg) ? 1 : 0;
        }
        configService.touchSyncTime(projectId);
        return imported;
    }

    @SuppressWarnings("unchecked")
    private boolean upsertIssue(Long projectId, Map<String, Object> issue, Map<String, Object> cfg) {
        try {
            String key = (String) issue.get("key");
            Map<String, Object> fields = (Map<String, Object>) issue.getOrDefault("fields", Map.of());
            String summary = (String) fields.getOrDefault("summary", key);
            String description = (String) fields.getOrDefault("description", "");
            String jiraStatus = readNested(fields, "status", "name");
            String priority = readNested(fields, "priority", "name");
            String severity = mapSeverity(priority);
            String status = mapBack(cfg, jiraStatus);

            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM bug_ticket WHERE project_id = ? AND external_key = ? AND source = 'JIRA'",
                    Integer.class, projectId, key);
            if (exists != null && exists > 0) {
                jdbc.update("""
                        UPDATE bug_ticket SET title = ?, description = ?, severity = ?, status = ?,
                            updated_at = now() WHERE project_id = ? AND external_key = ? AND source = 'JIRA'
                        """, summary, description, severity, status, projectId, key);
            } else {
                jdbc.update("""
                        INSERT INTO bug_ticket(project_id, title, description, severity, status,
                            source, external_key, found_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'JIRA', ?, ?, COALESCE(?, now()), now())
                        """, projectId, summary, description, severity, status, key,
                        fields.get("reporter") instanceof Map<?, ?> rep && rep.get("displayName") != null
                                ? String.valueOf(rep.get("displayName")) : "",
                        parseIso((String) fields.get("created")));
            }
            return true;
        } catch (Exception e) {
            log.warn("jira upsert issue failed: {}", e.getMessage());
            return false;
        }
    }

    /** Push a new local bug to Jira; stores external_key on success. */
    @SuppressWarnings("unchecked")
    public void pushNewBug(Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap(
                "SELECT * FROM bug_ticket WHERE id = ?", bugId);
        Long projectId = ((Number) bug.get("project_id")).longValue();
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled"))
                || "JIRA".equals(bug.get("source"))) {
            return; // already synced or not configured
        }
        try {
            Map<String, Object> body = Map.of("fields", Map.of(
                    "project", Map.of("key", cfg.get("jiraProjectKey")),
                    "summary", bug.get("title"),
                    "description", String.valueOf(bug.get("description")),
                    "issuetype", Map.of("name", cfg.get("issueType"))));
            Map<String, Object> resp = jiraPost(cfg, "/rest/api/2/issue", body);
            if (resp != null && resp.get("key") != null) {
                jdbc.update("""
                        UPDATE bug_ticket SET source = 'JIRA', external_key = ?, updated_at = now()
                        WHERE id = ?
                        """, resp.get("key"), bugId);
            }
        } catch (Exception e) {
            log.warn("jira push new bug failed: bugId={} {}", bugId, e.getMessage());
        }
    }

    /** Push a local status change as a Jira transition. */
    @SuppressWarnings("unchecked")
    public void pushStatus(Long bugId, String toStatus) {
        Map<String, Object> bug = jdbc.queryForMap(
                "SELECT * FROM bug_ticket WHERE id = ?", bugId);
        String externalKey = (String) bug.get("external_key");
        if (externalKey == null) {
            pushNewBug(bugId); // not yet in Jira — create it first
            bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);
            externalKey = (String) bug.get("external_key");
            if (externalKey == null) {
                return; // creation failed (Jira unavailable) — local flow unaffected
            }
        }
        Long projectId = ((Number) bug.get("project_id")).longValue();
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled"))) {
            return;
        }
        String target = mapForward(cfg, toStatus);
        if (target == null) {
            return; // no mapping for this status — skip
        }
        try {
            Map<String, Object> transitions = jiraGet(cfg, "/rest/api/2/issue/" + externalKey + "/transitions", null);
            if (transitions == null) {
                return;
            }
            List<Map<String, Object>> list = (List<Map<String, Object>>) transitions.getOrDefault("transitions", List.of());
            for (Map<String, Object> t : list) {
                String name = String.valueOf(t.get("name"));
                if (name.toLowerCase().contains(target.toLowerCase())
                        || name.equalsIgnoreCase(target)) {
                    jiraPost(cfg, "/rest/api/2/issue/" + externalKey + "/transitions",
                            Map.of("transition", Map.of("id", t.get("id"))));
                    log.info("jira transition pushed: bug={} {} → {}", bugId, externalKey, target);
                    return;
                }
            }
            log.info("jira transition not found for {} → {} (available: {})",
                    externalKey, target, list.stream().map(t -> t.get("name")).toList());
        } catch (Exception e) {
            log.warn("jira push status failed: bugId={} {}", bugId, e.getMessage());
        }
    }

    // ==================== HTTP helpers ====================

    private Map<String, Object> jiraGet(Map<String, Object> cfg, String path, String query) {
        return request(cfg, path + (query != null ? "?" + query : ""), "GET", null);
    }

    private Map<String, Object> jiraPost(Map<String, Object> cfg, String path, Map<String, Object> body) {
        return request(cfg, path, "POST", body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(Map<String, Object> cfg, String urlPath, String method, Map<String, Object> body) {
        try {
            String base = String.valueOf(cfg.get("baseUrl")).replaceAll("/+$", "");
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    (cfg.get("username") + ":" + cfg.get("apiToken")).getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(base + urlPath))
                    .header("Authorization", auth)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15));
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("jira {} {} → {}: {}", method, urlPath, resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return null;
            }
            return resp.body().isBlank() ? Map.of() : mapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            log.warn("jira request failed: {} {}", method, urlPath, e);
            return null;
        }
    }

    // ==================== mapping helpers ====================

    @SuppressWarnings("unchecked")
    private String mapForward(Map<String, Object> cfg, String evoStatus) {
        Object map = cfg.get("statusMap");
        if (!(map instanceof Map<?, ?> m)) {
            return null;
        }
        Object v = ((Map<String, Object>) m).get(evoStatus);
        return v != null ? String.valueOf(v) : null;
    }

    @SuppressWarnings("unchecked")
    private String mapBack(Map<String, Object> cfg, String jiraStatus) {
        Object map = cfg.get("statusMap");
        if (!(map instanceof Map<?, ?> m) || jiraStatus == null) {
            return "OPEN";
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            if (String.valueOf(e.getValue()).equalsIgnoreCase(jiraStatus)) {
                return e.getKey();
            }
        }
        return "OPEN";
    }

    private String mapSeverity(String jiraPriority) {
        if (jiraPriority == null) return "P2";
        String p = jiraPriority.toLowerCase();
        if (p.contains("highest") || p.contains("blocker")) return "P0";
        if (p.contains("high") || p.contains("critical")) return "P1";
        if (p.contains("low") || p.contains("trivial") || p.contains("minor")) return "P3";
        return "P2";
    }

    private static String readNested(Map<String, Object> map, String key, String field) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) {
            Object f = ((Map<?, ?>) m).get(field);
            return f != null ? String.valueOf(f) : null;
        }
        return null;
    }

    private static String parseIso(String iso) {
        if (iso == null || iso.length() < 19) return null;
        return iso.substring(0, 19).replace('T', ' ') + "+00";
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
