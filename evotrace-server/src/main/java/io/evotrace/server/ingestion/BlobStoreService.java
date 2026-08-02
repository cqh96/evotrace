package io.evotrace.server.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blob storage for large payloads (diff > 256KB), referenced by
 * {@code Envelope.blobRef} / {@code change_file.diff_blob_ref} and consumed by
 * CodeReview and AI summaries.
 * <p>
 * MVP keeps blobs in memory with a UUID key — blobs are lost on restart and
 * consumers must degrade gracefully. TODO(M2): replace with MinIO S3 client.
 */
@Service
public class BlobStoreService {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreService.class);

    private final Map<String, String> blobStore = new ConcurrentHashMap<>();

    public String put(String content) {
        String ref = UUID.randomUUID().toString();
        blobStore.put(ref, content);
        log.info("blob stored: ref={} size={}", ref, content.length());
        return ref;
    }

    public String get(String ref) {
        return blobStore.get(ref);
    }

    public int size() {
        return blobStore.size();
    }
}
