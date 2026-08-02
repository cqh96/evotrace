package io.evotrace.server.ingestion;

import io.evotrace.common.Result;
import io.evotrace.server.project.ApiCredential;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Accepts large payloads (diff > 256KB) and returns a blobRef
 * for use in Envelope.blobRef. Storage is in-memory (see
 * {@link BlobStoreService}); M2 will store in MinIO.
 */
@RestController
@RequestMapping("/open-api/v1")
public class BlobController {

    private static final Logger log = LoggerFactory.getLogger(BlobController.class);

    private final IngestionService ingestionService;
    private final BlobStoreService blobStoreService;

    public BlobController(IngestionService ingestionService, BlobStoreService blobStoreService) {
        this.ingestionService = ingestionService;
        this.blobStoreService = blobStoreService;
    }

    @PostMapping("/blobs")
    public Result<Map<String, String>> upload(@RequestHeader("X-EvoTrace-Api-Key") String apiKey,
                                              @RequestHeader("X-EvoTrace-Signature") String signature,
                                              @RequestBody String content,
                                              HttpServletRequest request) {
        // Same authentication channel as /events: API key + HMAC over the raw body
        String rawBody = (String) request.getAttribute(RawBodyCaptureFilter.RAW_BODY_ATTR);
        Result<ApiCredential> validation = ingestionService.validate(apiKey, signature, rawBody);
        if (!validation.success()) {
            return Result.fail(validation.code(), validation.message());
        }

        String blobRef = blobStoreService.put(content);
        log.info("blob uploaded: ref={} size={}", blobRef, content.length());
        return Result.ok(Map.of("blobRef", blobRef));
    }
}
