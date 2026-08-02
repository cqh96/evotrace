package io.evotrace.server.change;

import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * Handles CONFIG_CHANGE events (configuration file / property changes).
 * Persisted as a timeline-visible change event; content-level diffing is
 * covered by the snapshot engine.
 */
@Component
public class ConfigChangeHandler extends SimpleEventHandler {

    public ConfigChangeHandler(ChangeEventRepository changeEventRepository,
                               ProjectRepository projectRepository) {
        super(changeEventRepository, projectRepository);
    }

    @Override
    public EventType supportedType() {
        return EventType.CONFIG_CHANGE;
    }
}
