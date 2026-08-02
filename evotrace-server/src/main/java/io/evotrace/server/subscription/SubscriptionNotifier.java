package io.evotrace.server.subscription;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.server.change.ChangeFile;
import io.evotrace.server.change.ChangeFileRepository;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bridges persisted ChangeEvents to {@link SubscriptionMatcher} — the missing
 * call site that makes subscription rules actually fire notifications.
 * <p>
 * Runs asynchronously (outside the handler's transaction) so a slow or failing
 * webhook notification never blocks the ingestion pipeline. All exceptions are
 * swallowed with logging: notification failures must not trigger Kafka retries.
 */
@Component
public class SubscriptionNotifier {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionNotifier.class);

    private final ProjectRepository projectRepository;
    private final ChangeFileRepository changeFileRepository;
    private final SubscriptionMatcher subscriptionMatcher;

    public SubscriptionNotifier(ProjectRepository projectRepository,
                                ChangeFileRepository changeFileRepository,
                                SubscriptionMatcher subscriptionMatcher) {
        this.projectRepository = projectRepository;
        this.changeFileRepository = changeFileRepository;
        this.subscriptionMatcher = subscriptionMatcher;
    }

    @Async
    public void notifyAsync(Envelope envelope, String eventId) {
        try {
            Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                    .map(Project::getId).orElse(null);
            if (projectId == null) {
                log.debug("skip notification for unknown project: {}", envelope.projectKey());
                return;
            }

            String eventType = envelope.eventType().name();
            String appKey = envelope.appKey();
            String author = envelope.payload() != null
                    ? (String) envelope.payload().get("authorName") : null;

            List<ChangeFile> files = changeFileRepository.findByEventId(eventId);
            if (files.isEmpty()) {
                subscriptionMatcher.matchAndNotify(projectId, eventId, eventType, null, appKey, author);
            } else {
                for (ChangeFile file : files) {
                    subscriptionMatcher.matchAndNotify(projectId, eventId, eventType,
                            file.getFilePath(), appKey, author);
                }
            }
        } catch (Exception e) {
            // Never propagate: notification failures must not poison the event pipeline
            log.error("subscription notification failed: eventId={}", eventId, e);
        }
    }
}
