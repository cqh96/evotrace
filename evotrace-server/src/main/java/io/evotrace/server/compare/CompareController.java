package io.evotrace.server.compare;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Version compare report: stats aggregated from change events between the two
 * releases' release time, plus snapshot item diffs when snapshots exist (M2).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/compare")
public class CompareController {

    private final JdbcTemplate jdbcTemplate;

    public CompareController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Result<Map<String, Object>> compare(@PathVariable String projectKey,
                                               @RequestParam String from,
                                               @RequestParam String to) {
        Map<String, Object> range = jdbcTemplate.queryForMap("""
                SELECT f.released_at AS from_at, t.released_at AS to_at
                FROM release f, release t
                JOIN project p ON p.id = t.project_id AND p.project_key = ?
                WHERE f.project_id = t.project_id AND f.version = ? AND t.version = ?
                """, projectKey, from, to);

        Map<String, Object> stats = jdbcTemplate.queryForMap("""
                SELECT count(DISTINCT c.id) AS commits,
                       coalesce(sum(f.add_lines), 0) AS "addLines",
                       coalesce(sum(f.del_lines), 0) AS "delLines",
                       count(DISTINCT f.file_path) AS "filesChanged"
                FROM change_event c
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                LEFT JOIN change_file f ON f.event_id = c.event_id
                WHERE c.occurred_at > ? AND c.occurred_at <= ?
                """, projectKey, range.get("from_at"), range.get("to_at"));

        List<Map<String, Object>> changes = jdbcTemplate.queryForList("""
                SELECT c.event_type AS type, c.commit_sha AS sha, c.author,
                       c.occurred_at AS "occurredAt",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                         LIMIT 1) AS summary
                FROM change_event c JOIN project p ON p.id = c.project_id AND p.project_key = ?
                WHERE c.occurred_at > ? AND c.occurred_at <= ?
                ORDER BY c.occurred_at
                """, projectKey, range.get("from_at"), range.get("to_at"));

        // Snapshot dimension diffs (if snapshots exist for these releases)
        List<Map<String, Object>> apis = querySnapshotDiff(projectKey, from, to, "API");
        List<Map<String, Object>> dependencies = querySnapshotDiff(projectKey, from, to, "DEPENDENCY");
        List<Map<String, Object>> configs = querySnapshotDiff(projectKey, from, to, "CONFIG");
        List<Map<String, Object>> schemas = querySnapshotDiff(projectKey, from, to, "SCHEMA");

        return Result.ok(Map.of(
                "fromVersion", from,
                "toVersion", to,
                "stats", stats,
                "changes", changes,
                "apis", apis,
                "dependencies", dependencies,
                "configs", configs,
                "schemas", schemas));
    }

    private List<Map<String, Object>> querySnapshotDiff(String projectKey, String from, String to, String category) {
        try {
            return jdbcTemplate.queryForList("""
                    SELECT si.identity_key AS "identityKey",
                           COALESCE(t.change_flag, 'UNCHANGED') AS "changeFlag",
                           si.content_json AS content
                    FROM snapshot_item si
                    JOIN snapshot_item_ref r ON r.item_hash = si.content_hash
                    JOIN snapshot s ON s.id = r.snapshot_id
                    JOIN release rel ON rel.id = s.release_id
                    JOIN project p ON p.id = rel.project_id AND p.project_key = ?
                    WHERE rel.version = ? AND si.category = ?
                    """, projectKey, to, category);
        } catch (Exception e) {
            return List.of();
        }
    }
}
