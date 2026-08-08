package io.evotrace.server.ingestion;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blob storage for large payloads (diff), referenced by
 * {@code Envelope.blobRef} / {@code change_file.diff_blob_ref}.
 * <p>
 * Keeps an in-memory cache and optionally persists to {@code evotrace.blob.dir}
 * so diffs survive process restarts (MinIO migration remains a TODO).
 */
@Service
public class BlobStoreService {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreService.class);

    private final Map<String, String> blobStore = new ConcurrentHashMap<>();

    @Value("${evotrace.blob.dir:}")
    private String blobDir;

    private Path dir;

    @PostConstruct
    void init() {
        if (blobDir == null || blobDir.isBlank()) {
            log.warn("evotrace.blob.dir not set — blobs are memory-only and lost on restart");
            return;
        }
        try {
            dir = Path.of(blobDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            log.info("blob store directory ready: {}", dir);
        } catch (IOException e) {
            log.error("failed to init blob dir {}: {}", blobDir, e.getMessage());
            dir = null;
        }
    }

    public String put(String content) {
        String ref = UUID.randomUUID().toString();
        String value = content != null ? content : "";
        blobStore.put(ref, value);
        if (dir != null) {
            try {
                Files.writeString(dir.resolve(ref), value, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("failed to persist blob {}: {}", ref, e.getMessage());
            }
        }
        log.info("blob stored: ref={} size={} durable={}", ref, value.length(), dir != null);
        return ref;
    }

    public String get(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String mem = blobStore.get(ref);
        if (mem != null) {
            return mem;
        }
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve(ref).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            blobStore.put(ref, content);
            return content;
        } catch (IOException e) {
            log.warn("failed to read blob {}: {}", ref, e.getMessage());
            return null;
        }
    }

    public int size() {
        return blobStore.size();
    }
}
