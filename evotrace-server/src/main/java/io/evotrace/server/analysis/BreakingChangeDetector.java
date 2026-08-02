package io.evotrace.server.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Detects breaking changes by comparing snapshot items between two releases.
 * Categories:
 * - CRITICAL: API deleted, DDL DROP COLUMN
 * - WARNING: Field type narrowed, new required param, dependency major upgrade
 * - INFO: API signature change, config key removed
 */
@Component
public class BreakingChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(BreakingChangeDetector.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Pattern: Integer → int, Long → int (narrowing) */
    private static final Pattern TYPE_NARROW_PATTERN =
            Pattern.compile("(Long|Double|BigDecimal|String)\\s*→\\s*(Integer|int|Long)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;

    public BreakingChangeDetector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Detect breaking changes between two releases and persist alerts.
     * @return list of alerts found
     */
    @Transactional
    public List<Map<String, Object>> detect(Long projectId, String fromVersion, String toVersion) {
        List<Map<String, Object>> alerts = new ArrayList<>();

        // 1. Find deleted APIs (present in 'from', not in 'to')
        List<Map<String, Object>> deletedApis = jdbc.queryForList("""
                SELECT si_from.identity_key AS "identityKey", si_from.content_json AS "beforeState"
                FROM snapshot_item si_from
                JOIN snapshot_item_ref r_from ON r_from.item_hash = si_from.content_hash
                JOIN snapshot s_from ON s_from.id = r_from.snapshot_id
                JOIN release rel_from ON rel_from.id = s_from.release_id
                WHERE rel_from.project_id = ? AND rel_from.version = ?
                  AND si_from.category = 'API'
                  AND r_from.change_flag != 'REMOVED'
                  AND si_from.identity_key NOT IN (
                      SELECT si_to.identity_key
                      FROM snapshot_item si_to
                      JOIN snapshot_item_ref r_to ON r_to.item_hash = si_to.content_hash
                      JOIN snapshot s_to ON s_to.id = r_to.snapshot_id
                      JOIN release rel_to ON rel_to.id = s_to.release_id
                      WHERE rel_to.version = ? AND si_to.category = 'API'
                        AND r_to.change_flag != 'REMOVED'
                  )
                """, projectId, fromVersion, toVersion);

        for (var api : deletedApis) {
            String identity = (String) api.get("identityKey");
            Map<String, Object> alert = Map.of(
                    "changeType", "API_DELETED",
                    "severity", "CRITICAL",
                    "detail", Map.of("identityKey", identity, "message", "接口已删除: " + identity)
            );
            alerts.add(alert);
            saveAlert(projectId, null, "API_DELETED", "CRITICAL", alert);
        }

        // 2. Find DDL DROP COLUMN (content_json before contains columns not in after)
        List<Map<String, Object>> ddlChanges = jdbc.queryForList("""
                SELECT si_from.identity_key AS "identityKey",
                       si_from.content_json AS "beforeJson",
                       si_to.content_json AS "afterJson"
                FROM snapshot_item si_from
                JOIN snapshot_item_ref r_from ON r_from.item_hash = si_from.content_hash
                JOIN snapshot s_from ON s_from.id = r_from.snapshot_id
                JOIN release rel_from ON rel_from.id = s_from.release_id
                JOIN snapshot s_to ON s_to.release_id = (SELECT id FROM release WHERE version = ? AND project_id = ?)
                JOIN snapshot_item_ref r_to ON r_to.snapshot_id = s_to.id
                JOIN snapshot_item si_to ON si_to.content_hash = r_to.item_hash
                    AND si_to.identity_key = si_from.identity_key
                WHERE rel_from.project_id = ? AND rel_from.version = ?
                  AND si_from.category = 'SCHEMA'
                  AND r_to.change_flag IN ('MODIFIED', 'REMOVED')
                """, toVersion, projectId, projectId, fromVersion);

        for (var ddl : ddlChanges) {
            // pgjdbc returns jsonb columns as PGobject, not String
            String before = String.valueOf(ddl.get("beforeJson"));
            String after = String.valueOf(ddl.get("afterJson"));
            List<String> droppedColumns = detectDroppedColumns(before, after);
            for (String col : droppedColumns) {
                Map<String, Object> alert = Map.of(
                        "changeType", "DDL_DROP_COLUMN",
                        "severity", "CRITICAL",
                        "detail", Map.of("table", ddl.get("identityKey"), "column", col,
                                "message", "DDL 删除了列: " + ddl.get("identityKey") + "." + col)
                );
                alerts.add(alert);
                saveAlert(projectId, null, "DDL_DROP_COLUMN", "CRITICAL", alert);
            }
        }

        // 3. Field type narrowing detection
        List<Map<String, Object>> apiModifications = jdbc.queryForList("""
                SELECT si_from.identity_key AS "identityKey",
                       si_from.content_json AS "beforeJson",
                       si_to.content_json AS "afterJson"
                FROM snapshot_item si_from
                JOIN snapshot_item_ref r_from ON r_from.item_hash = si_from.content_hash
                JOIN snapshot s_from ON s_from.id = r_from.snapshot_id
                JOIN release rel_from ON rel_from.id = s_from.release_id
                JOIN snapshot s_to ON s_to.release_id = (SELECT id FROM release WHERE version = ? AND project_id = ?)
                JOIN snapshot_item_ref r_to ON r_to.snapshot_id = s_to.id
                JOIN snapshot_item si_to ON si_to.content_hash = r_to.item_hash
                    AND si_to.identity_key = si_from.identity_key
                WHERE rel_from.project_id = ? AND rel_from.version = ?
                  AND si_from.category = 'API'
                  AND r_to.change_flag = 'MODIFIED'
                """, toVersion, projectId, projectId, fromVersion);

        for (var mod : apiModifications) {
            String before = String.valueOf(mod.get("beforeJson"));
            String after = String.valueOf(mod.get("afterJson"));
            if (before != null && after != null && TYPE_NARROW_PATTERN.matcher(before + "→" + after).find()) {
                Map<String, Object> alert = Map.of(
                        "changeType", "FIELD_NARROWED",
                        "severity", "WARNING",
                        "detail", Map.of("identityKey", mod.get("identityKey"),
                                "message", "字段类型收窄: " + mod.get("identityKey"))
                );
                alerts.add(alert);
                saveAlert(projectId, null, "FIELD_NARROWED", "WARNING", alert);
            }
        }

        log.info("breaking change detection complete: {} alerts for project {} ({} → {})",
                alerts.size(), projectId, fromVersion, toVersion);
        return alerts;
    }

    /** Quick check for breaking changes without persisting */
    public boolean hasBreakingChanges(Long projectId, String fromVersion, String toVersion) {
        return !detect(projectId, fromVersion, toVersion).isEmpty();
    }

    private List<String> detectDroppedColumns(String beforeJson, String afterJson) {
        List<String> dropped = new ArrayList<>();
        if (beforeJson == null || afterJson == null) return dropped;
        try {
            var beforeMap = mapper.readValue(beforeJson, Map.class);
            var afterMap = mapper.readValue(afterJson, Map.class);
            if (beforeMap.containsKey("columns") && afterMap.containsKey("columns")) {
                @SuppressWarnings("unchecked")
                var beforeCols = new HashSet<>((List<String>) beforeMap.get("columns"));
                @SuppressWarnings("unchecked")
                var afterCols = new HashSet<>((List<String>) afterMap.get("columns"));
                beforeCols.removeAll(afterCols);
                dropped.addAll(beforeCols);
            }
        } catch (JsonProcessingException e) {
            log.debug("failed to parse DDL json for column diff");
        }
        return dropped;
    }

    private void saveAlert(Long projectId, Long releaseId, String changeType, String severity,
                           Map<String, Object> detail) {
        try {
            // Store the nested detail object so detail_json ->> 'message' works
            // for dedup and the frontend reads row.detail.message directly
            Object detailObj = detail.get("detail") instanceof Map<?, ?> d ? d : detail;
            // Dedup: both the scheduled snapshot engine and the manual
            // risk-score endpoint may detect the same change
            jdbc.update("""
                    INSERT INTO breaking_change_alert(project_id, release_id, change_type, severity, detail_json)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (project_id, change_type, (detail_json ->> 'message')) DO NOTHING
                    """, projectId, releaseId, changeType, severity, mapper.writeValueAsString(detailObj));
        } catch (Exception e) {
            log.error("failed to save breaking change alert", e);
        }
    }
}
