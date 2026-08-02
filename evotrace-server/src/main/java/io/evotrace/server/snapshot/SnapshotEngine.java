package io.evotrace.server.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.analysis.BreakingChangeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Snapshot engine — the missing writer for the snapshot tables.
 * <p>
 * Runs daily ({@code evotrace.snapshot.cron}) and, for every (project, app)
 * with releases, builds a FULL snapshot of the app's state at each release
 * that doesn't have one yet: items are collected from the {@code change_file}
 * history window and the latest {@code inventory_report}, deduped
 * content-addressed into {@code snapshot_item}, linked via
 * {@code snapshot_item_ref} with ADDED/REMOVED/UNCHANGED flags against the
 * previous snapshot, and then {@link BreakingChangeDetector} is triggered so
 * breaking-change alerts are generated automatically.
 * <p>
 * DELTA snapshots / the full-baseline-interval heuristic are left for M2;
 * releases without an app (project-level tags) are not snapshotted.
 */
@Component
public class SnapshotEngine {

    private static final Logger log = LoggerFactory.getLogger(SnapshotEngine.class);
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1900-01-01T00:00:00Z");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final List<SnapshotItemProvider> providers;
    private final BreakingChangeDetector breakingChangeDetector;

    public SnapshotEngine(JdbcTemplate jdbc, List<SnapshotItemProvider> providers,
                          BreakingChangeDetector breakingChangeDetector) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.breakingChangeDetector = breakingChangeDetector;
    }

    @Scheduled(cron = "${evotrace.snapshot.cron:0 0 3 * * *}")
    public void runSnapshotCycle() {
        List<Map<String, Object>> apps = jdbc.queryForList("""
                SELECT DISTINCT r.project_id AS "projectId", r.app_id AS "appId"
                FROM release r WHERE r.app_id IS NOT NULL
                """);
        int total = 0;
        for (var app : apps) {
            Long projectId = ((Number) app.get("projectId")).longValue();
            Long appId = ((Number) app.get("appId")).longValue();
            total += snapshotPendingReleases(projectId, appId);
        }
        log.info("snapshot cycle complete: {} snapshot(s) built across {} app(s)", total, apps.size());
    }

    /** Build snapshots for all releases of (project, app) that lack one. Returns count. */
    @Transactional
    public int snapshotPendingReleases(Long projectId, Long appId) {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT r.id, r.version, r.released_at AS "releasedAt"
                FROM release r
                WHERE r.project_id = ? AND r.app_id = ?
                  AND NOT EXISTS (SELECT 1 FROM snapshot s WHERE s.release_id = r.id)
                ORDER BY r.released_at, r.id
                """, projectId, appId);

        int built = 0;
        for (var release : pending) {
            try {
                buildSnapshot(projectId, appId,
                        ((Number) release.get("id")).longValue(),
                        (String) release.get("version"),
                        toOffsetDateTime(release.get("releasedAt")));
                built++;
            } catch (Exception e) {
                log.error("failed to build snapshot for release {} of project {}/app {}: {}",
                        release.get("id"), projectId, appId, e.getMessage());
            }
        }
        return built;
    }

    private void buildSnapshot(Long projectId, Long appId, Long releaseId, String version,
                               OffsetDateTime releasedAt) throws Exception {
        // Previous release of the same app → snapshot window + from-version
        Map<String, Object> prev = findPreviousRelease(projectId, appId, releaseId, releasedAt);
        OffsetDateTime from = prev != null ? toOffsetDateTime(prev.get("releasedAt")) : EPOCH;

        // 1. Collect item drafts from all providers
        Map<String, SnapshotDraft> drafts = new HashMap<>();
        for (SnapshotItemProvider provider : providers) {
            for (SnapshotDraft draft : provider.collect(projectId, appId, from, releasedAt, version)) {
                drafts.put(hash(draft), draft);
            }
        }

        // 2. Snapshot row (BUILDING → DONE)
        jdbc.update("INSERT INTO snapshot(release_id, app_id, type, status) VALUES (?, ?, 'FULL', 'BUILDING')",
                releaseId, appId);
        Long snapshotId = jdbc.queryForObject("SELECT currval('snapshot_id_seq')", Long.class);

        // 3. Persist items (content-addressed, platform-wide dedup)
        Map<String, Long> previousHashes = prev != null
                ? previousSnapshotHashes(prev)
                : Map.of();
        int added = 0;
        for (Map.Entry<String, SnapshotDraft> entry : drafts.entrySet()) {
            SnapshotDraft draft = entry.getValue();
            jdbc.update("""
                    INSERT INTO snapshot_item(content_hash, category, identity_key, content_json)
                    VALUES (?, ?, ?, ?::jsonb)
                    ON CONFLICT (content_hash) DO NOTHING
                    """, entry.getKey(), draft.category(), draft.identityKey(),
                    mapper.writeValueAsString(new TreeMap<>(draft.content())));
        }

        // 4. Link refs with change flags vs the previous snapshot
        for (Map.Entry<String, SnapshotDraft> entry : drafts.entrySet()) {
            String flag = previousHashes.containsKey(entry.getKey()) ? "UNCHANGED" : "ADDED";
            jdbc.update("INSERT INTO snapshot_item_ref(snapshot_id, item_hash, change_flag) VALUES (?, ?, ?)",
                    snapshotId, entry.getKey(), flag);
            if ("ADDED".equals(flag)) added++;
        }
        int removed = 0;
        for (String prevHash : previousHashes.keySet()) {
            if (!drafts.containsKey(prevHash)) {
                jdbc.update("INSERT INTO snapshot_item_ref(snapshot_id, item_hash, change_flag) VALUES (?, ?, 'REMOVED')",
                        snapshotId, prevHash);
                removed++;
            }
        }

        jdbc.update("UPDATE snapshot SET item_count = ?, status = 'DONE' WHERE id = ?",
                drafts.size(), snapshotId);
        log.info("snapshot built: release={} version={} items={} (+{} / -{})",
                releaseId, version, drafts.size(), added, removed);

        // 5. Trigger breaking-change detection (previous release → this release),
        //    only once the previous release's snapshot is available
        if (prev != null && hasDoneSnapshot(((Number) prev.get("id")).longValue())) {
            try {
                List<Map<String, Object>> alerts = breakingChangeDetector.detect(
                        projectId, (String) prev.get("version"), version);
                log.info("breaking change detection: {} alert(s) for {} → {}", alerts.size(),
                        prev.get("version"), version);
            } catch (Exception e) {
                log.error("breaking change detection failed for {} → {}: {}",
                        prev.get("version"), version, e.getMessage());
            }
        }
    }

    private boolean hasDoneSnapshot(Long releaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM snapshot WHERE release_id = ? AND status = 'DONE'",
                Integer.class, releaseId);
        return count != null && count > 0;
    }

    private Map<String, Object> findPreviousRelease(Long projectId, Long appId, Long releaseId,
                                                    OffsetDateTime releasedAt) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.id, r.version, r.released_at AS "releasedAt"
                FROM release r
                WHERE r.project_id = ? AND r.app_id = ? AND r.id != ?
                  AND r.released_at <= ?
                ORDER BY r.released_at DESC, r.id DESC LIMIT 1
                """, projectId, appId, releaseId, releasedAt);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Long> previousSnapshotHashes(Map<String, Object> prevRelease) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ref.item_hash AS "hash"
                FROM snapshot_item_ref ref
                JOIN snapshot s ON s.id = ref.snapshot_id
                WHERE s.release_id = ? AND s.status = 'DONE'
                """, prevRelease.get("id"));
        Map<String, Long> hashes = new HashMap<>();
        for (var row : rows) {
            hashes.put((String) row.get("hash"), 1L);
        }
        return hashes;
    }

    /** pgjdbc returns TIMESTAMPTZ columns as java.sql.Timestamp in queryForList. */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    /** Content hash: category + identity + canonical content JSON. */
    private String hash(SnapshotDraft draft) {
        try {
            String canonical = mapper.writeValueAsString(new TreeMap<>(draft.content()));
            String payload = draft.category() + "|" + draft.identityKey() + "|" + canonical;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash snapshot item", e);
        }
    }

    /** Trigger a snapshot cycle manually (used by tests/ops). */
    public void runNow() {
        runSnapshotCycle();
    }
}
