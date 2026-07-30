package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.server.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consumes raw events, routes them by eventType to the matching
 * {@link ChangeEventHandler}, syncs the Neo4j graph and dispatches AI tasks.
 * <p>
 * Event routing uses a strategy registry built from all Spring-injected
 * {@code ChangeEventHandler} beans.
 */
@Component
public class ChangeEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventConsumer.class);

    private final Map<io.evotrace.protocol.envelope.EventType, ChangeEventHandler> handlerRegistry;

    public ChangeEventConsumer(List<ChangeEventHandler> handlers) {
        this.handlerRegistry = handlers.stream()
                .collect(Collectors.toMap(ChangeEventHandler::supportedType, Function.identity()));
    }

    @KafkaListener(topics = IngestionService.TOPIC_RAW_EVENTS, groupId = "change-service")
    public void onEvent(Envelope envelope) {
        ChangeEventHandler handler = handlerRegistry.get(envelope.eventType());
        if (handler == null) {
            log.warn("no handler registered for event type: {} (eventId={})",
                    envelope.eventType(), envelope.eventId());
            return;
        }
        try {
            String eventId = handler.handle(envelope);
            log.info("event processed: type={} eventId={}", envelope.eventType(), eventId);
        } catch (Exception e) {
            log.error("failed to process event: type={} eventId={}",
                    envelope.eventType(), envelope.eventId(), e);
            // TODO(M1): retry with exponential backoff, then DLT after 3 failures
            throw e;
        }
    }
}
