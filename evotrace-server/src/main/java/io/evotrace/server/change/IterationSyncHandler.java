package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.iteration.Iteration;
import io.evotrace.server.iteration.IterationRepository;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Handles ITERATION_SYNC events: upserts the iteration identified by
 * (project, source, externalKey) and links the change event to it.
 */
@Component
public class IterationSyncHandler extends SimpleEventHandler {

    private final IterationRepository iterationRepository;

    public IterationSyncHandler(ChangeEventRepository changeEventRepository,
                                ProjectRepository projectRepository,
                                IterationRepository iterationRepository) {
        super(changeEventRepository, projectRepository);
        this.iterationRepository = iterationRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.ITERATION_SYNC;
    }

    @Override
    protected ChangeEvent buildEvent(Envelope envelope, Long projectId) {
        ChangeEvent event = super.buildEvent(envelope, projectId);

        String externalKey = payloadString(envelope, "externalKey");
        String title = payloadString(envelope, "title");
        if (externalKey == null || title == null) {
            log.warn("ITERATION_SYNC payload missing externalKey/title, skipping iteration upsert: eventId={}",
                    envelope.eventId());
            return event;
        }

        String src = payloadString(envelope, "source");
        if (src == null || src.isBlank()) src = "SYNC";
        final String source = src;

        Iteration iteration = iterationRepository
                .findByProjectIdAndSourceAndExternalKey(projectId, source, externalKey)
                .orElseGet(() -> {
                    Iteration it = new Iteration();
                    it.setProjectId(projectId);
                    it.setSource(source);
                    it.setExternalKey(externalKey);
                    return it;
                });
        iteration.setTitle(title);
        iteration.setStatus(payloadString(envelope, "status"));
        iteration.setUrl(payloadString(envelope, "url"));
        iteration.setSyncedAt(envelope.occurredAt());
        iterationRepository.save(iteration);

        event.setIterationId(iteration.getId());
        return event;
    }
}
