package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Template-method base for event handlers that persist a {@code change_event}
 * row. Handles the common flow: idempotency check, projectKey → projectId
 * resolution, event persistence and the {@link #buildEvent}/{@link #postPersist}
 * extension hooks.
 * <p>
 * Subclasses implement {@link #supportedType()} and override the hooks where
 * event-type-specific fields or side effects (change files, releases,
 * iterations, AI tasks) are needed.
 */
public abstract class SimpleEventHandler implements ChangeEventHandler {

    protected static final Logger log = LoggerFactory.getLogger(SimpleEventHandler.class);

    protected final ChangeEventRepository changeEventRepository;
    protected final ProjectRepository projectRepository;

    protected SimpleEventHandler(ChangeEventRepository changeEventRepository,
                                 ProjectRepository projectRepository) {
        this.changeEventRepository = changeEventRepository;
        this.projectRepository = projectRepository;
    }

    @Override
    @Transactional
    public String handle(Envelope envelope) {
        // Idempotency: skip if already persisted
        if (changeEventRepository.existsByIdempotencyKey(envelope.idempotencyKey())) {
            log.info("duplicate event skipped: {}", envelope.idempotencyKey());
            return envelope.eventId();
        }

        Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                .map(Project::getId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + envelope.projectKey()));

        ChangeEvent event = buildEvent(envelope, projectId);
        changeEventRepository.save(event);
        postPersist(event, envelope);

        log.info("event persisted: type={} eventId={}", envelope.eventType(), event.getEventId());
        return event.getEventId();
    }

    /**
     * Populate the change_event row (before save). Defaults cover the fields
     * shared by all event types; subclasses override to add type-specific ones.
     */
    protected ChangeEvent buildEvent(Envelope envelope, Long projectId) {
        ChangeEvent event = new ChangeEvent();
        event.setProjectId(projectId);
        event.setEventId(envelope.eventId());
        event.setIdempotencyKey(envelope.idempotencyKey());
        event.setEventType(envelope.eventType().name());
        event.setOccurredAt(envelope.occurredAt());
        event.setSummaryStatus("DONE"); // most event types don't need an AI summary

        if (envelope.payload() != null) {
            event.setBranch((String) envelope.payload().get("branch"));
            event.setCommitSha((String) envelope.payload().get("commitSha"));
            event.setCommitMessage((String) envelope.payload().get("message"));
        }
        String author = payloadString(envelope, "authorName");
        event.setAuthor(author != null ? author : "system");
        return event;
    }

    /**
     * Side effects after the event row is persisted (files, releases,
     * iterations, AI task dispatch). Default is a no-op.
     */
    protected void postPersist(ChangeEvent event, Envelope envelope) {
    }

    /** Read a String value from the envelope payload, null-safe. */
    protected static String payloadString(Envelope envelope, String key) {
        return envelope.payload() != null ? (String) envelope.payload().get(key) : null;
    }
}
