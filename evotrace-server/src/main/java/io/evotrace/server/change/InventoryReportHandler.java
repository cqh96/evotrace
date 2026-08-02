package io.evotrace.server.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.application.Application;
import io.evotrace.server.application.ApplicationRepository;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Handles INVENTORY_REPORT events: creates or updates the application record,
 * persists the inventory as a change event for timeline visibility, and writes
 * the payload (apis/dependencies/configs/ddl) to {@code inventory_report} —
 * the data source for the snapshot engine.
 */
@Component
public class InventoryReportHandler extends SimpleEventHandler {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ApplicationRepository applicationRepository;
    private final JdbcTemplate jdbc;

    public InventoryReportHandler(ChangeEventRepository changeEventRepository,
                                  ProjectRepository projectRepository,
                                  ApplicationRepository applicationRepository,
                                  JdbcTemplate jdbc) {
        super(changeEventRepository, projectRepository);
        this.applicationRepository = applicationRepository;
        this.jdbc = jdbc;
    }

    @Override
    public EventType supportedType() {
        return EventType.INVENTORY_REPORT;
    }

    @Override
    protected ChangeEvent buildEvent(Envelope envelope, Long projectId) {
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

        ChangeEvent event = super.buildEvent(envelope, projectId);
        event.setAppId(app.getId());
        return event;
    }

    @Override
    protected void postPersist(ChangeEvent event, Envelope envelope) {
        persistInventoryReport(event.getProjectId(), event.getAppId(), envelope);
    }

    /** Persist the payload to inventory_report (consumed by the snapshot engine). */
    private void persistInventoryReport(Long projectId, Long appId, Envelope envelope) {
        try {
            Map<String, Object> payload = envelope.payload() != null ? envelope.payload() : Map.of();
            jdbc.update("""
                    INSERT INTO inventory_report(project_id, app_id, event_id, base_commit, version,
                        tech_stack, api_json, dependency_json, config_json, ddl_json, reported_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """, projectId, appId, envelope.eventId(),
                    payload.get("baseCommit"), payload.get("version"), payload.get("techStack"),
                    mapper.writeValueAsString(payload.getOrDefault("apis", List.of())),
                    mapper.writeValueAsString(payload.getOrDefault("dependencies", List.of())),
                    mapper.writeValueAsString(payload.getOrDefault("configFingerprints", Map.of())),
                    mapper.writeValueAsString(payload.getOrDefault("ddlStatements", List.of())),
                    envelope.occurredAt());
        } catch (Exception e) {
            // Persisting the report must not fail the event pipeline
            log.warn("failed to persist inventory_report for event {}: {}", envelope.eventId(), e.getMessage());
        }
    }
}
