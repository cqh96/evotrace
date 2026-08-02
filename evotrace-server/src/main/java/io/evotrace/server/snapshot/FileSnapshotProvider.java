package io.evotrace.server.snapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds snapshot items from {@code change_file} rows in the release window:
 * the latest state of each file touched between the previous and current
 * release. Category is inferred from the file path.
 */
@Component
public class FileSnapshotProvider implements SnapshotItemProvider {

    private final JdbcTemplate jdbc;

    public FileSnapshotProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "file";
    }

    @Override
    public List<SnapshotDraft> collect(Long projectId, Long appId,
                                       OffsetDateTime fromInclusive, OffsetDateTime toInclusive,
                                       String version) {
        // DISTINCT ON: latest change_file state per path within the window
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT ON (cf.file_path)
                       cf.file_path AS "path", cf.change_kind AS "kind",
                       cf.add_lines AS "addLines", cf.del_lines AS "delLines"
                FROM change_file cf
                JOIN change_event ce ON ce.event_id = cf.event_id
                WHERE ce.project_id = ? AND ce.app_id = ? AND ce.occurred_at > ? AND ce.occurred_at <= ?
                ORDER BY cf.file_path, ce.occurred_at DESC
                """, projectId, appId, fromInclusive, toInclusive);

        List<SnapshotDraft> drafts = new ArrayList<>();
        for (var row : rows) {
            String path = (String) row.get("path");
            Map<String, Object> content = Map.of(
                    "path", path,
                    "changeKind", row.get("kind"),
                    "addLines", row.get("addLines"),
                    "delLines", row.get("delLines")
            );
            drafts.add(new SnapshotDraft(classify(path), path, content));
        }
        return drafts;
    }

    /** Infer snapshot category from the file path. */
    static String classify(String path) {
        if (path == null) return "STRUCTURE";
        String p = path.toLowerCase();
        if (p.endsWith(".sql")) return "SCHEMA";
        if (p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties")
                || p.endsWith(".json") || p.endsWith(".toml") || p.endsWith(".xml")) {
            return "CONFIG";
        }
        if (p.endsWith("controller.java") || p.contains("/api/")
                || p.contains("/controller/") || p.contains("/controllers/")) {
            return "API";
        }
        return "STRUCTURE";
    }
}
