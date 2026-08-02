package io.evotrace.server.snapshot;

import java.util.Map;

/**
 * A candidate snapshot item before hashing/dedup: category + stable identity
 * key within a snapshot + the content that will be hashed (canonical JSON).
 */
public record SnapshotDraft(String category, String identityKey, Map<String, Object> content) {
}
