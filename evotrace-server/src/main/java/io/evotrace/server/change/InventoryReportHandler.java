package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.application.Application;
import io.evotrace.server.application.ApplicationRepository;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles INVENTORY_REPORT events: creates or updates the application record
 * and persists the inventory snapshot as change events.
 * The actual snapshot diff logic (snapshot_item + snapshot_item_ref) is
 * triggered asynchronously by the snapshot engine (M2).
 */
@Component
public class InventoryReportHandler implements ChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReportHandler.class);

    private final ChangeEventRepository changeEventRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationRepository applicationRepository;

    public InventoryReportHandler(ChangeEventRepository changeEventRepository,
                                  ProjectRepository projectRepository,
                                  ApplicationRepository applicationRepository) {
        this.changeEventRepository = changeEventRepository;
        this.projectRepository = projectRepository;
        this.applicationRepository = applicationRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.INVENTORY_REPORT;
    }

    @Override
    @Transactional
    public String handle(Envelope envelope) {
        if (changeEventRepository.existsByIdempotencyKey(envelope.idempotencyKey())) {
            log.info("duplicate inventory report skipped: {}", envelope.idempotencyKey());
            return envelope.eventId();
        }

        Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                .map(p -> p.getId())
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + envelope.projectKey()));

        // Upsert application record
        final String appKey = envelope.appKey() != null ? envelope.appKey() : "default";
        Application app = applicationRepository
                .findByProjectIdAndAppKey(projectId, appKey)
                .orElseGet(() -> {
                    Application a = new Application();
                    a.setProjectId(projectId);
                    a.setAppKey(appKey);
                    a.setName(appKey);
                    return a;
                });

        // Update tech stack from payload if present
        if (envelope.payload() != null && envelope.payload().get("techStack") instanceof String ts) {
            app.setTechStack(ts);
        }
        applicationRepository.save(app);

        // Persist as a change event for timeline visibility
        ChangeEvent event = new ChangeEvent();
        event.setProjectId(projectId);
        event.setAppId(app.getId());
        event.setEventId(envelope.eventId());
        event.setIdempotencyKey(envelope.idempotencyKey());
        event.setEventType(envelope.eventType().name());
        event.setAuthor("system");
        event.setOccurredAt(envelope.occurredAt());
        event.setSummaryStatus("DONE"); // inventory reports don't need AI summary
        changeEventRepository.save(event);

        log.info("inventory report persisted: project={} app={}", envelope.projectKey(), appKey);
        return event.getEventId();
    }
}
