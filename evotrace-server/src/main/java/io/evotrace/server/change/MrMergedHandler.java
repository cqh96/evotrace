package io.evotrace.server.change;

import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Handles MR_MERGED events (GitLab merge_request / GitHub pull_request).
 * The merge is persisted as a timeline-visible change event; the underlying
 * code changes arrive as separate CODE_COMMIT events.
 */
@Component
public class MrMergedHandler extends SimpleEventHandler {

    public MrMergedHandler(ChangeEventRepository changeEventRepository,
                           ProjectRepository projectRepository) {
        super(changeEventRepository, projectRepository);
    }

    @Override
    public EventType supportedType() {
        return EventType.MR_MERGED;
    }
}
