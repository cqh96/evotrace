package io.evotrace.server.ingestion;

import io.evotrace.common.Result;
import io.evotrace.protocol.envelope.Envelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Open ingestion endpoint for all integrators (SDK / CLI / Open API).
 * Authentication: API Key + HMAC-SHA256 signature (X-EvoTrace-Signature)
 * computed over the raw request body (captured by {@link RawBodyCaptureFilter}).
 */
@RestController
@RequestMapping("/open-api/v1")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/events")
    public Result<Map<String, Object>> report(@RequestHeader("X-EvoTrace-Api-Key") String apiKey,
                                              @RequestHeader("X-EvoTrace-Signature") String signature,
                                              @RequestBody Envelope envelope,
                                              HttpServletRequest request) {
        String rawBody = (String) request.getAttribute(RawBodyCaptureFilter.RAW_BODY_ATTR);
        return ingestionService.accept(apiKey, signature, rawBody, envelope);
    }
}
