package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.project.ProjectRepository;
import io.evotrace.server.trace.LinkRuleEngineService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Handles MR_MERGED events (GitLab merge_request / GitHub pull_request).
 * The merge is persisted as a timeline-visible change event; the underlying
 * code changes arrive as separate CODE_COMMIT events.
 * <p>
 * After persist, applies the trace link rules to the MR title / branch.
 */
@Component
public class MrMergedHandler extends SimpleEventHandler {

    private final LinkRuleEngineService linkRuleEngine;

    public MrMergedHandler(ChangeEventRepository changeEventRepository,
                           ProjectRepository projectRepository,
                           LinkRuleEngineService linkRuleEngine) {
        super(changeEventRepository, projectRepository);
        this.linkRuleEngine = linkRuleEngine;
    }

    @Override
    public EventType supportedType() {
        return EventType.MR_MERGED;
    }

    @Override
    protected void postPersist(ChangeEvent event, Envelope envelope) {
        // Trace v2：对 MR title / branch 应用关联规则（失败不影响主流程）
        try {
            Map<String, Object> payload = envelope.payload();
            String title = payload != null ? (String) payload.get("title") : null;
            if (title == null && payload != null) {
                title = (String) payload.get("message");
            }
            String branch = payload != null ? (String) payload.get("branch") : null;
            String author = payload != null ? (String) payload.get("authorName") : null;
            linkRuleEngine.onMr(event.getProjectId(), event.getEventId(), title, branch, author);
        } catch (Exception e) {
            log.warn("trace link rule on mr skipped: {}", e.getMessage());
        }
    }
}