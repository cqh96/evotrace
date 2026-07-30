package io.evotrace.server.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.iteration.Iteration;
import io.evotrace.server.iteration.IterationRepository;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles CODE_COMMIT and MR_MERGED events: persists change_event + change_file rows,
 * extracts iteration key from commit message, and dispatches AI summary task.
 */
@Component
public class CommitHandler implements ChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CommitHandler.class);

    /** Matches common requirement key patterns: REQ-1234, JIRA-4567, #789 */
    private static final Pattern ITERATION_KEY_PATTERN =
            Pattern.compile("(?:REQ|JIRA|ISSUE|TASK|STORY)[-_:]?\\d{3,}|#\\d{3,}",
                    Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ChangeEventRepository changeEventRepository;
    private final ChangeFileRepository changeFileRepository;
    private final ProjectRepository projectRepository;
    private final IterationRepository iterationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public CommitHandler(ChangeEventRepository changeEventRepository,
                         ChangeFileRepository changeFileRepository,
                         ProjectRepository projectRepository,
                         IterationRepository iterationRepository,
                         KafkaTemplate<String, String> kafkaTemplate) {
        this.changeEventRepository = changeEventRepository;
        this.changeFileRepository = changeFileRepository;
        this.projectRepository = projectRepository;
        this.iterationRepository = iterationRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public EventType supportedType() {
        return EventType.CODE_COMMIT;
    }

    @Override
    @Transactional
    public String handle(Envelope envelope) {
        // Idempotency: skip if already persisted
        if (changeEventRepository.existsByIdempotencyKey(envelope.idempotencyKey())) {
            log.info("duplicate event skipped: {}", envelope.idempotencyKey());
            return envelope.eventId();
        }

        CodeCommitPayload payload = convertPayload(envelope.payload());

        // Resolve project id
        Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                .map(p -> p.getId())
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + envelope.projectKey()));

        // Persist change_event
        ChangeEvent event = new ChangeEvent();
        event.setProjectId(projectId);
        event.setEventId(envelope.eventId());
        event.setIdempotencyKey(envelope.idempotencyKey());
        event.setEventType(envelope.eventType().name());
        event.setBranch(payload.branch());
        event.setCommitSha(payload.commitSha());
        event.setAuthor(payload.authorName());
        event.setBlobRef(envelope.blobRef());
        event.setOccurredAt(envelope.occurredAt());
        event.setSummaryStatus("PENDING");

        // Link iteration by extracting key from commit message
        if (payload.message() != null) {
            Long iterationId = resolveIteration(projectId, payload.message());
            event.setIterationId(iterationId);
        }

        changeEventRepository.save(event);

        // Persist changed files
        if (payload.files() != null) {
            for (CodeCommitPayload.FileChange fc : payload.files()) {
                ChangeFile file = new ChangeFile();
                file.setEventId(event.getEventId());
                file.setFilePath(fc.newPath() != null ? fc.newPath() : fc.oldPath());
                file.setOldPath(fc.oldPath());
                file.setChangeKind(fc.kind() != null ? fc.kind().name() : "MODIFIED");
                file.setAddLines(fc.addLines());
                file.setDelLines(fc.delLines());
                file.setDiffBlobRef(fc.diffBlobRef());
                changeFileRepository.save(file);
            }
        }

        // Dispatch AI summary task
        dispatchAiTask(event);

        log.info("commit persisted: eventId={} files={}", event.getEventId(),
                payload.files() != null ? payload.files().size() : 0);
        return event.getEventId();
    }

    private Long resolveIteration(Long projectId, String message) {
        Matcher m = ITERATION_KEY_PATTERN.matcher(message);
        if (!m.find()) return null;

        String key = m.group();
        return iterationRepository.findByProjectIdAndSourceAndExternalKey(projectId, "JIRA", key)
                .map(Iteration::getId)
                .orElse(null);
    }

    private void dispatchAiTask(ChangeEvent event) {
        try {
            Map<String, Object> task = Map.of(
                    "taskType", "SUMMARIZE",
                    "eventId", event.getEventId(),
                    "projectId", event.getProjectId(),
                    "eventType", event.getEventType()
            );
            kafkaTemplate.send("evo.tasks.ai", event.getEventId(), mapper.writeValueAsString(task));
        } catch (Exception e) {
            log.error("failed to dispatch AI task for event {}", event.getEventId(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private CodeCommitPayload convertPayload(Map<String, Object> payload) {
        // Deserialize nested FileChange list from Map
        List<Map<String, Object>> filesRaw = (List<Map<String, Object>>) payload.get("files");
        List<CodeCommitPayload.FileChange> files = null;
        if (filesRaw != null) {
            files = filesRaw.stream().map(f -> {
                String kindStr = (String) f.get("kind");
                return new CodeCommitPayload.FileChange(
                        (String) f.get("oldPath"),
                        (String) f.get("newPath"),
                        kindStr != null ? CodeCommitPayload.FileChange.ChangeKind.valueOf(kindStr) : null,
                        f.get("addLines") instanceof Number n ? n.intValue() : 0,
                        f.get("delLines") instanceof Number n ? n.intValue() : 0,
                        (String) f.get("diffBlobRef")
                );
            }).toList();
        }

        return new CodeCommitPayload(
                (String) payload.get("repoUrl"),
                (String) payload.get("branch"),
                (String) payload.get("commitSha"),
                (List<String>) payload.get("parentShas"),
                (String) payload.get("authorName"),
                (String) payload.get("authorEmail"),
                (String) payload.get("message"),
                files
        );
    }
}
