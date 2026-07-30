package io.evotrace.server.ai;

/**
 * Deserialized from the Kafka AI task message.
 */
public record AiTaskPayload(
        String taskType,
        String eventId,
        Long projectId,
        String eventType
) {}
