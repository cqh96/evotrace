package io.evotrace.server.change;

import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Handles DEPLOY_RECORD events (deployment records from CI/CD pipelines).
 * Persisted as a timeline-visible change event so deployments are traceable
 * alongside code changes and releases.
 */
@Component
public class DeployRecordHandler extends SimpleEventHandler {

    public DeployRecordHandler(ChangeEventRepository changeEventRepository,
                               ProjectRepository projectRepository) {
        super(changeEventRepository, projectRepository);
    }

    @Override
    public EventType supportedType() {
        return EventType.DEPLOY_RECORD;
    }
}
