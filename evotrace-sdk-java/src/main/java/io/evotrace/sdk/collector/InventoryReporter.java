package io.evotrace.sdk.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventSource;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.InventoryReportPayload;
import io.evotrace.sdk.autoconfigure.EvotraceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collects api/dependency/config inventory after ApplicationReadyEvent and
 * reports an INVENTORY_REPORT envelope to the EvoTrace server.
 * Runs on a virtual thread; failures are logged and the event is not retried
 * (the next heartbeat will report fresh data).
 */
public class InventoryReporter {

    private static final Logger log = LoggerFactory.getLogger(InventoryReporter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final EvotraceProperties properties;
    private final List<InventoryCollector> collectors;
    private final HttpClient httpClient;

    public InventoryReporter(EvotraceProperties properties, List<InventoryCollector> collectors) {
        this.properties = properties;
        this.collectors = collectors != null ? collectors : List.of();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("EvoTrace reporter initialized for project [{}] with {} collector(s) -> {}",
                properties.getProjectKey(), this.collectors.size(), properties.getServerUrl());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.startVirtualThread(this::report);
    }

    @SuppressWarnings("unchecked")
    private void report() {
        log.info("EvoTrace inventory report starting...");
        for (InventoryCollector collector : collectors) {
            try {
                InventoryReportPayload payload = collector.collect();
                if (payload == null) continue;

                String appKey = properties.getAppKey();
                if (appKey == null || appKey.isBlank()) {
                    appKey = "default";
                }

                // Convert payload to Map for the Envelope
                Map<String, Object> payloadMap = mapper.convertValue(payload, Map.class);

                Envelope envelope = new Envelope(
                        Envelope.CURRENT_VERSION,
                        UUID.randomUUID().toString(),
                        properties.getProjectKey(),
                        appKey,
                        EventType.INVENTORY_REPORT,
                        OffsetDateTime.now(),
                        EventSource.JAVA_SDK,
                        "sdk:" + properties.getProjectKey() + ":" + appKey + ":" + System.currentTimeMillis(),
                        payloadMap,
                        null
                );

                String body = mapper.writeValueAsString(envelope);
                String signature = sign(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(properties.getServerUrl() + "/open-api/v1/events"))
                        .header("X-EvoTrace-Api-Key", nvl(properties.getApiKey()))
                        .header("X-EvoTrace-Signature", signature)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                log.info("inventory reported: category={} app={} status={}",
                        collector.category(), appKey, response.statusCode());
            } catch (Exception e) {
                log.error("failed to report inventory for collector {}", collector.category(), e);
            }
        }
    }

    private String sign(String body) {
        try {
            String secret = properties.getApiSecret();
            if (secret == null || secret.isBlank()) return "unsigned";
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            log.error("signing failed", e);
            return "sign-error";
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
