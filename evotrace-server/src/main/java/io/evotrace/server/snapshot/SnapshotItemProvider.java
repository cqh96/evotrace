package io.evotrace.server.snapshot;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Supplies snapshot items for a (project, app) release window. Providers are
 * auto-collected by {@link SnapshotEngine}; the two implementations are
 * file-based ({@code change_file}) and inventory-based ({@code inventory_report}).
 */
public interface SnapshotItemProvider {

    /** Provider name, used in logs. */
    String name();

    /**
     * Collect items representing the app's state at the given release.
     *
     * @param fromInclusive  start of the change window (previous release time)
     * @param toInclusive    the release time
     * @param version        the release version (inventory providers match on it)
     */
    List<SnapshotDraft> collect(Long projectId, Long appId,
                                OffsetDateTime fromInclusive, OffsetDateTime toInclusive,
                                String version);
}
