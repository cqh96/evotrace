package io.evotrace.server.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.plugin.ParserPlugin;
import io.evotrace.server.plugin.ParserPluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds snapshot items from the latest {@code inventory_report} known at the
 * release time — the per-version API / dependency / config / DDL inventory
 * reported by the SDK or CLI. These are the items that make API deletion,
 * field narrowing and DDL column-drop detection actually meaningful.
 */
@Component
public class InventorySnapshotProvider implements SnapshotItemProvider {

    private static final Logger log = LoggerFactory.getLogger(InventorySnapshotProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Matches table names in common DDL statements. */
    private static final Pattern DDL_TABLE_PATTERN =
            Pattern.compile("(?:ALTER TABLE|CREATE TABLE|DROP TABLE)\\s+(?:IF EXISTS\\s+)?([\\w\\.]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_PATTERN =
            Pattern.compile("(?:ADD COLUMN|DROP COLUMN|ADD|DROP)\\s+([\\w\\.]+)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final ParserPluginRegistry pluginRegistry;

    public InventorySnapshotProvider(JdbcTemplate jdbc, ParserPluginRegistry pluginRegistry) {
        this.jdbc = jdbc;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public String name() {
        return "inventory";
    }

    @Override
    public List<SnapshotDraft> collect(Long projectId, Long appId,
                                       OffsetDateTime fromInclusive, OffsetDateTime toInclusive,
                                       String version) {
        // Prefer the report reported for this exact release version, then fall
        // back to the latest known report at release time.
        List<Map<String, Object>> reports = jdbc.queryForList("""
                SELECT api_json, dependency_json, config_json, ddl_json
                FROM inventory_report
                WHERE app_id = ? AND version = ? AND reported_at <= ?
                ORDER BY reported_at DESC, id DESC LIMIT 1
                """, appId, version, toInclusive);
        if (reports.isEmpty()) {
            reports = jdbc.queryForList("""
                    SELECT api_json, dependency_json, config_json, ddl_json
                    FROM inventory_report
                    WHERE app_id = ? AND reported_at <= ?
                    ORDER BY reported_at DESC, id DESC LIMIT 1
                    """, appId, toInclusive);
        }
        if (reports.isEmpty()) {
            return List.of();
        }
        Map<String, Object> report = reports.get(0);
        List<SnapshotDraft> drafts = new ArrayList<>();
        try {
            // pgjdbc returns jsonb columns as PGobject, not String
            collectApis(jsonString(report.get("api_json")), drafts);
            collectDependencies(jsonString(report.get("dependency_json")), drafts);
            collectConfigs(jsonString(report.get("config_json")), drafts);
            collectDdl(jsonString(report.get("ddl_json")), drafts);
            // 插件解析:内置解析完成后,让市场安装的插件对原始清单再做扩展解析
            collectPluginItems(ParserPlugin.Category.API,
                    jsonString(report.get("api_json")), "inventory:apis", drafts);
            collectPluginItems(ParserPlugin.Category.DEPENDENCY,
                    jsonString(report.get("dependency_json")), "inventory:dependencies", drafts);
            collectPluginItems(ParserPlugin.Category.CONFIG,
                    jsonString(report.get("config_json")), "inventory:configs", drafts);
            collectPluginItems(ParserPlugin.Category.DDL,
                    jsonString(report.get("ddl_json")), "inventory:ddl", drafts);
        } catch (Exception e) {
            log.warn("failed to parse inventory report for app {}: {}", appId, e.getMessage());
        }
        return drafts;
    }

    /** 调用指定类别的已注册插件解析原始清单,产物以带溯源信息并入快照草稿。 */
    private void collectPluginItems(ParserPlugin.Category category,
                                    String rawJson, String feature, List<SnapshotDraft> drafts) {
        if (rawJson == null || rawJson.isBlank()) return;
        try {
            for (ParserPluginRegistry.PluginItem pi : pluginRegistry.parse(category, rawJson, feature)) {
                ParserPlugin.ParseItem item = pi.item();
                Map<String, Object> content = new java.util.LinkedHashMap<>();
                content.put("detail", item.detail());
                content.put("source", "plugin");
                content.put("pluginId", pi.pluginId());
                drafts.add(new SnapshotDraft(item.type(), item.name(), content));
            }
        } catch (Exception e) {
            log.warn("plugin parse failed on {}: {}", feature, e.getMessage());
        }
    }

    /** PGobject → JSON text; NULL stays NULL. */
    private static String jsonString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void collectApis(String apiJson, List<SnapshotDraft> drafts) throws Exception {
        if (apiJson == null) return;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apis = mapper.readValue(apiJson, List.class);
        for (Map<String, Object> api : apis) {
            String method = String.valueOf(api.getOrDefault("httpMethod", "GET"));
            String path = String.valueOf(api.getOrDefault("path", ""));
            Map<String, Object> content = Map.of(
                    "method", method,
                    "path", path,
                    "signatureHash", api.getOrDefault("signatureHash", ""),
                    "schemaFingerprint", api.getOrDefault("schemaFingerprint", "")
            );
            drafts.add(new SnapshotDraft("API", method + " " + path, content));
        }
    }

    private void collectDependencies(String dependencyJson, List<SnapshotDraft> drafts) throws Exception {
        if (dependencyJson == null) return;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = mapper.readValue(dependencyJson, List.class);
        for (Map<String, Object> dep : deps) {
            String group = String.valueOf(dep.getOrDefault("group", ""));
            String artifact = String.valueOf(dep.getOrDefault("artifact", ""));
            Map<String, Object> content = Map.of(
                    "group", group,
                    "artifact", artifact,
                    "version", dep.getOrDefault("version", ""),
                    "scope", dep.getOrDefault("scope", "")
            );
            drafts.add(new SnapshotDraft("DEPENDENCY", group + ":" + artifact, content));
        }
    }

    private void collectConfigs(String configJson, List<SnapshotDraft> drafts) throws Exception {
        if (configJson == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> configs = mapper.readValue(configJson, Map.class);
        for (Map.Entry<String, Object> entry : configs.entrySet()) {
            drafts.add(new SnapshotDraft("CONFIG", entry.getKey(),
                    Map.of("key", entry.getKey(), "fingerprint", String.valueOf(entry.getValue()))));
        }
    }

    private void collectDdl(String ddlJson, List<SnapshotDraft> drafts) throws Exception {
        if (ddlJson == null) return;
        @SuppressWarnings("unchecked")
        List<String> statements = mapper.readValue(ddlJson, List.class);
        for (String statement : statements) {
            String table = extractTable(statement);
            String identity = table != null
                    ? "table:" + table.toLowerCase()
                    : "ddl:" + sha256Short(statement);
            Map<String, Object> content = Map.of(
                    "statement", statement,
                    "columns", extractColumns(statement)
            );
            drafts.add(new SnapshotDraft("SCHEMA", identity, content));
        }
    }

    private String extractTable(String statement) {
        Matcher m = DDL_TABLE_PATTERN.matcher(statement);
        return m.find() ? m.group(1) : null;
    }

    /** Column names mentioned in the statement (best-effort, additive model). */
    private List<String> extractColumns(String statement) {
        List<String> columns = new ArrayList<>();
        Matcher m = COLUMN_PATTERN.matcher(statement);
        while (m.find()) {
            String column = m.group(1);
            if (!columns.contains(column)) columns.add(column);
        }
        return columns;
    }

    private String sha256Short(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
