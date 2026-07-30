package io.evotrace.server.ingestion;

import io.evotrace.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Accepts large payloads (diff > 256KB) and returns a blobRef
 * for use in Envelope.blobRef. MVP stores blobs in memory with a UUID key;
 * M2 will store in MinIO.
 */
@RestController
@RequestMapping("/open-api/v1")
public class BlobController {

    private static final Logger log = LoggerFactory.getLogger(BlobController.class);

    // TODO(M2): replace with MinIO S3 client
    private final Map<String, String> blobStore = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/blobs")
    public Result<Map<String, String>> upload(@RequestBody String content) {
        String blobRef = UUID.randomUUID().toString();
        blobStore.put(blobRef, content);
        log.info("blob uploaded: ref={} size={}", blobRef, content.length());
        return Result.ok(Map.of("blobRef", blobRef));
    }
}
