package io.evotrace.server.change;

import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Handles DDL_CHANGE events (database schema migrations).
 * Persisted as a timeline-visible change event; schema diffs (DROP COLUMN etc.)
 * are detected by the snapshot engine / BreakingChangeDetector.
 */
@Component
public class DdlChangeHandler extends SimpleEventHandler {

    public DdlChangeHandler(ChangeEventRepository changeEventRepository,
                            ProjectRepository projectRepository) {
        super(changeEventRepository, projectRepository);
    }

    @Override
    public EventType supportedType() {
        return EventType.DDL_CHANGE;
    }
}
