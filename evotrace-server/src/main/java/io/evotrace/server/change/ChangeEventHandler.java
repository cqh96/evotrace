package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;

/**
 * Strategy interface for handling different event types from the ingestion pipeline.
 * Spring injects all implementations into ChangeEventConsumer, which routes
 * each envelope to the matching handler.
 */
public interface ChangeEventHandler {

    /** Which event type this handler processes. */
    EventType supportedType();

    /**
     * Process the envelope: normalize to domain entities, persist, link iterations,
     * and publish downstream AI tasks.
     *
     * @return the persisted ChangeEvent's eventId for AI task linking
     */
    String handle(Envelope envelope);
}
