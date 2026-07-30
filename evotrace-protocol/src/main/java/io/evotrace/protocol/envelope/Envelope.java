package io.evotrace.protocol.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Unified ingestion protocol (v1). All integrators (SDK / CLI / Webhook / Open API)
 * report changes exclusively through this envelope.
 *
 * @param protocolVersion protocol version, currently "v1"
 * @param eventId         client-generated ULID/UUID
 * @param projectKey      project identifier
 * @param appKey          application identifier (nullable for project-level events)
 * @param eventType       strong typed event category
 * @param occurredAt      when the change actually happened
 * @param source          reporting channel
 * @param idempotencyKey  globally unique key for dedup, e.g. "gitlab:project/123:push:abc"
 * @param payload         event-type specific payload (schema per EventType).
 *                        Typed as Map to stay serializer-agnostic (Jackson 2 / Jackson 3).
 * @param blobRef         reference to external blob (diff larger than 256KB)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(
        String protocolVersion,
        String eventId,
        String projectKey,
        String appKey,
        EventType eventType,
        OffsetDateTime occurredAt,
        EventSource source,
        String idempotencyKey,
        Map<String, Object> payload,
        String blobRef
) {
    public static final String CURRENT_VERSION = "v1";
}
