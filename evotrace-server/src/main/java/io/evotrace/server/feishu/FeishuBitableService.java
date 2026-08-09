package io.evotrace.server.feishu;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feishu Bitable ↔ EvoTrace bidirectional sync (缺陷 + 测试用例).
 * <p>
 * - pullBugs / pullCases: list Bitable records → upsert bug_ticket / test_case
 *   (source='FEISHU', external_key=record_id), status mapped back via status_map.
 * - pushNewBug / pushStatus / pushNewCase: create / update Bitable records so
 *   local lifecycle changes are reflected in Feishu.
 * <p>
 * Every operation degrades gracefully: no config / disabled / unreachable Feishu
 * → logged and skipped, never blocking the local pipeline. Scheduled nightly
 * pull across all enabled projects. Runs alongside (not replacing) Jira sync.
 */
@Service
public class FeishuBitableService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBitableService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String BASE = "https://open.feishu.cn";

    private final JdbcTemplate jdbc;
    private final FeishuConfigService configService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    public FeishuBitableService(JdbcTemplate jdbc, FeishuConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    @Scheduled(cron = "${evotrace.feishu.pull-cron:0 40 0 * * *}")
    public void scheduledPull() {
        List<Long> projectIds = jdbc.queryForList(
                "SELECT project_id FROM project_feishu_config WHERE enabled = true", Long.class);
        for (Long projectId : projectIds) {
            try {
                Map<String, Object> r = pullAll(projectId);
                log.info("feishu pull: project={} bugs={} cases={}", projectId, r.get("bugs"), r.get("cases"));
            } catch (Exception e) {
                log.warn("feishu pull failed for project {}: {}", projectId, e.getMessage());
            }
        }
    }

    /** Pull both bugs and cases for a project. Returns imported counts. */
    public Map<String, Object> pullAll(Long projectId) {
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled"))) {
            return Map.of("bugs", 0, "cases", 0);
        }
        int bugs = cfg.get("bugTableId") != null ? pullBugs(projectId, cfg) : 0;
        int cases = cfg.get("caseTableId") != null ? pullCases(projectId, cfg) : 0;
        configService.touchSyncTime(projectId);
        return Map.of("bugs", bugs, "cases", cases);
    }

    // ==================== PULL ====================

    @SuppressWarnings("unchecked")
    public int pullBugs(Long projectId, Map<String, Object> cfg) {
        int imported = 0;
        for (Map<String, Object> record : listRecords(cfg, str(cfg.get("bugTableId")))) {
            Map<String, Object> fields = (Map<String, Object>) record.getOrDefault("fields", Map.of());
            String recordId = (String) record.get("record_id");
            String title = fieldText(fields, fieldName(cfg, "title"));
            if (title == null || title.isBlank()) {
                continue;
            }
            String severity = mapSeverity(fieldText(fields, fieldName(cfg, "severity")));
            String status = mapBack(cfg, fieldText(fields, fieldName(cfg, "status")));
            String description = fieldText(fields, fieldName(cfg, "description"));
            imported += upsertBug(projectId, recordId, title, description, severity, status) ? 1 : 0;
        }
        return imported;
    }

    private boolean upsertBug(Long projectId, String recordId, String title, String description,
                              String severity, String status) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM bug_ticket WHERE project_id = ? AND external_key = ? AND source = 'FEISHU'",
                    Integer.class, projectId, recordId);
            if (exists != null && exists > 0) {
                jdbc.update("""
                        UPDATE bug_ticket SET title = ?, description = ?, severity = ?, status = ?,
                            updated_at = now() WHERE project_id = ? AND external_key = ? AND source = 'FEISHU'
                        """, title, description, severity, status, projectId, recordId);
            } else {
                jdbc.update("""
                        INSERT INTO bug_ticket(project_id, title, description, severity, status,
                            source, external_key, found_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'FEISHU', ?, '飞书同步', now(), now())
                        """, projectId, title, description, severity, status, recordId);
            }
            return true;
        } catch (Exception e) {
            log.warn("feishu upsert bug failed: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public int pullCases(Long projectId, Map<String, Object> cfg) {
        int imported = 0;
        for (Map<String, Object> record : listRecords(cfg, str(cfg.get("caseTableId")))) {
            Map<String, Object> fields = (Map<String, Object>) record.getOrDefault("fields", Map.of());
            String recordId = (String) record.get("record_id");
            String title = fieldText(fields, fieldName(cfg, "title"));
            if (title == null || title.isBlank()) {
                continue;
            }
            String testType = fieldText(fields, fieldName(cfg, "testType"));
            String priority = fieldText(fields, fieldName(cfg, "priority"));
            String steps = fieldTextJSON(fields, fieldName(cfg, "steps"));
            imported += upsertCase(projectId, recordId, title, testType, priority, steps) ? 1 : 0;
        }
        return imported;
    }

    private boolean upsertCase(Long projectId, String recordId, String title, String testType,
                               String priority, String steps) {
        try {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM test_case WHERE project_id = ? AND external_key = ? AND source = 'FEISHU'",
                    Integer.class, projectId, recordId);
            String tt = normalizeTestType(testType);
            String pr = normalizePriority(priority);
            if (exists != null && exists > 0) {
                jdbc.update("""
                        UPDATE test_case SET title = ?, test_type = ?, priority = ?, steps = ?,
                            updated_at = now() WHERE project_id = ? AND external_key = ? AND source = 'FEISHU'
                        """, title, tt, pr, steps, projectId, recordId);
            } else {
                jdbc.update("""
                        INSERT INTO test_case(project_id, title, test_type, priority, steps, source, external_key,
                            created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'FEISHU', ?, '飞书同步', now(), now())
                        """, projectId, title, tt, pr, steps, recordId);
            }
            return true;
        } catch (Exception e) {
            log.warn("feishu upsert case failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== PUSH ====================

    /** Create a Bitable bug record for a new local bug; stores external_key on success. */
    public void pushNewBug(Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);
        pushRecord(bug, "bugTableId", "FEISHU",
                Map.of("title", bug.get("title"),
                       "description", bug.get("description"),
                       "severity", bug.get("severity"),
                       "status", bug.get("status")),
                bugId);
    }

    /** Push a local bug status change to its Bitable record. */
    @SuppressWarnings("unchecked")
    public void pushStatus(Long bugId, String toStatus) {
        Map<String, Object> bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);
        if (!"FEISHU".equals(bug.get("source")) || bug.get("external_key") == null) {
            return;
        }
        Long projectId = ((Number) bug.get("project_id")).longValue();
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled")) || cfg.get("bugTableId") == null) {
            return;
        }
        String target = mapForward(cfg, toStatus);
        if (target == null) {
            return;
        }
        updateRecord(cfg, str(cfg.get("bugTableId")), (String) bug.get("external_key"),
                Map.of(fieldName(cfg, "status"), target));
    }

    /** Create a Bitable test-case record for a new local case; stores external_key on success. */
    @SuppressWarnings("unchecked")
    public void pushNewCase(Long caseId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM test_case WHERE id = ?", caseId);
        pushRecord(row, "caseTableId", "FEISHU",
                Map.of("title", row.get("title"),
                       "testType", row.get("test_type"),
                       "priority", row.get("priority"),
                       "steps", row.get("steps")),
                caseId);
    }

    /** Shared create-record push for bugs and test cases. */
    private void pushRecord(Map<String, Object> row, String tableKey, String source,
                            Map<String, Object> fields, Long rowId) {
        Long projectId = ((Number) row.get("project_id")).longValue();
        Map<String, Object> cfg = configService.getConfig(projectId);
        if (cfg == null || !Boolean.TRUE.equals(cfg.get("enabled")) || cfg.get(tableKey) == null) {
            return;
        }
        if (source.equals(row.get("source")) && row.get("external_key") != null) {
            return; // already synced
        }
        try {
            String tableId = str(cfg.get(tableKey));
            Map<String, Object> body = Map.of("fields", buildFields(cfg, fields));
            Map<String, Object> resp = bitablePost(cfg, tableId, "", body);
            if (resp != null) {
                Map<String, Object> rec = (Map<String, Object>) resp.get("record");
                if (rec != null && rec.get("record_id") != null) {
                    String table = "test_case".equals(source) ? "test_case" : "bug_ticket";
                    jdbc.update("UPDATE " + table
                                    + " SET source = 'FEISHU', external_key = ?, updated_at = now() WHERE id = ?",
                            rec.get("record_id"), rowId);
                }
            }
        } catch (Exception e) {
            log.warn("feishu push record failed: type={} id={} {}", source, rowId, e.getMessage());
        }
    }

    /** Map semantic EvoTrace fields → Bitable column names (via field_map, fallback to key). */
    private Map<String, Object> buildFields(Map<String, Object> cfg, Map<String, Object> fields) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String col = fieldName(cfg, e.getKey());
            if (col != null && e.getValue() != null) {
                out.put(col, e.getValue());
            }
        }
        return out;
    }

    // ==================== HTTP (Bitable + token) ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRecords(Map<String, Object> cfg, String tableId) {
        List<Map<String, Object>> all = new ArrayList<>();
        String token = token(cfg);
        if (token == null) {
            return all;
        }
        String pageToken = "";
        try {
            do {
                String query = "page_size=100" + (pageToken.isEmpty() ? "" : "&page_token=" + pageToken);
                URI uri = URI.create(BASE + "/open-apis/bitable/v1/apps/" + str(cfg.get("appToken"))
                        + "/tables/" + tableId + "/records?" + query);
                HttpRequest req = HttpRequest.newBuilder().uri(uri)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 400) {
                    log.warn("feishu list records {} → {}: {}", tableId, resp.statusCode(), snippet(resp.body()));
                    break;
                }
                Map<String, Object> data = (Map<String, Object>) mapper.readValue(resp.body(), Map.class)
                        .get("data");
                if (data == null) {
                    break;
                }
                all.addAll((List<Map<String, Object>>) data.getOrDefault("items", List.of()));
                pageToken = (String) data.getOrDefault("page_token", "");
                if (!Boolean.TRUE.equals(data.get("has_more"))) {
                    break;
                }
            } while (!pageToken.isEmpty());
        } catch (Exception e) {
            log.warn("feishu list records failed: {}", e.getMessage());
        }
        return all;
    }

    private Map<String, Object> updateRecord(Map<String, Object> cfg, String tableId, String recordId,
                                             Map<String, Object> fields) {
        String token = token(cfg);
        if (token == null) {
            return null;
        }
        try {
            URI uri = URI.create(BASE + "/open-apis/bitable/v1/apps/" + str(cfg.get("appToken"))
                    + "/tables/" + tableId + "/records/" + recordId);
            HttpRequest req = HttpRequest.newBuilder().uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("fields", fields))))
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("feishu update record {} → {}: {}", recordId, resp.statusCode(), snippet(resp.body()));
                return null;
            }
            return mapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            log.warn("feishu update record failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> bitablePost(Map<String, Object> cfg, String tableId, String path,
                                            Map<String, Object> body) {
        String token = token(cfg);
        if (token == null) {
            return null;
        }
        try {
            URI uri = URI.create(BASE + "/open-apis/bitable/v1/apps/" + str(cfg.get("appToken"))
                    + "/tables/" + tableId + "/records" + path);
            HttpRequest req = HttpRequest.newBuilder().uri(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("feishu post record → {}: {}", resp.statusCode(), snippet(resp.body()));
                return null;
            }
            return mapper.readValue(resp.body(), Map.class);
        } catch (Exception e) {
            log.warn("feishu post record failed: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String token(Map<String, Object> cfg) {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedToken;
        }
        String appId = str(cfg.get("appId"));
        String appSecret = rawSecret(((Number) cfg.get("projectId")).longValue());
        if (appId == null || appSecret == null) {
            return null;
        }
        try {
            URI uri = URI.create(BASE + "/open-apis/auth/v3/tenant_access_token/internal");
            HttpRequest req = HttpRequest.newBuilder().uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("app_id", appId, "app_secret", appSecret))))
                    .timeout(Duration.ofSeconds(15)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> data = mapper.readValue(resp.body(), Map.class);
            if (resp.statusCode() >= 400 || !"0".equals(String.valueOf(data.get("code")))) {
                log.warn("feishu token failed: code={} msg={}", data.get("code"), data.get("msg"));
                return null;
            }
            String t = (String) data.get("tenant_access_token");
            Object expire = data.get("expire");
            cachedToken = t;
            tokenExpireAt = System.currentTimeMillis()
                    + (expire instanceof Number n ? n.longValue() - 300 : 3600) * 1000;
            return t;
        } catch (Exception e) {
            log.warn("feishu token request failed: {}", e.getMessage());
            return null;
        }
    }

    private String rawSecret(Long projectId) {
        java.util.List<String> rows = jdbc.queryForList(
                "SELECT app_secret FROM project_feishu_config WHERE project_id = ?", String.class, projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== mapping helpers ====================

    @SuppressWarnings("unchecked")
    private String fieldName(Map<String, Object> cfg, String key) {
        Object map = cfg.get("fieldMap");
        if (map instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return key;
    }

    /** Extract a plain-text value from a Bitable field (string / number / select). */
    private static String fieldText(Map<String, Object> fields, String col) {
        Object v = fields.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            return list.isEmpty() ? null : String.valueOf(list.get(0));
        }
        return String.valueOf(v);
    }

    /** Extract a JSON-ish value (e.g. steps) preserving structure where possible. */
    private static String fieldTextJSON(Map<String, Object> fields, String col) {
        Object v = fields.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            try {
                return mapper.writeValueAsString(list);
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }

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
    private String mapBack(Map<String, Object> cfg, String feishuStatus) {
        Object map = cfg.get("statusMap");
        if (!(map instanceof Map<?, ?> m) || feishuStatus == null) {
            return "OPEN";
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            if (String.valueOf(e.getValue()).equalsIgnoreCase(feishuStatus)) {
                return e.getKey();
            }
        }
        return "OPEN";
    }

    private String mapSeverity(String feishuPriority) {
        if (feishuPriority == null) {
            return "P2";
        }
        String p = feishuPriority.trim().toUpperCase();
        if (p.startsWith("P0") || p.contains("阻塞")) return "P0";
        if (p.startsWith("P1") || p.contains("严重") || p.contains("高")) return "P1";
        if (p.startsWith("P3") || p.contains("轻微") || p.contains("低")) return "P3";
        return "P2";
    }

    private static String normalizePriority(String p) {
        if (p == null) {
            return "P2";
        }
        String s = p.trim().toUpperCase();
        return (s.startsWith("P0") || s.startsWith("P1") || s.startsWith("P2") || s.startsWith("P3"))
                ? s.substring(0, 2) : "P2";
    }

    private static String normalizeTestType(String t) {
        if (t == null) {
            return "FUNCTIONAL";
        }
        String s = t.trim().toUpperCase();
        return (s.equals("FUNCTIONAL") || s.equals("REGRESSION") || s.equals("API")
                || s.equals("PERFORMANCE") || s.equals("SECURITY")) ? s : "FUNCTIONAL";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}