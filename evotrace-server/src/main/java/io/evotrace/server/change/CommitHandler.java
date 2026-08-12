package io.evotrace.server.change;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.governance.AutomationRuleService;
import io.evotrace.server.iteration.Iteration;
import io.evotrace.server.iteration.IterationRepository;
import io.evotrace.server.project.ProjectRepository;
import io.evotrace.server.trace.LinkRuleEngineService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles CODE_COMMIT events: persists change_event + change_file rows,
 * extracts iteration key from commit message, and dispatches AI tasks
 * (summary, and code review when enabled).
 */
@Component
public class CommitHandler extends SimpleEventHandler {

    /** Matches common requirement key patterns: REQ-1234, JIRA-4567, #789 */
    private static final Pattern ITERATION_KEY_PATTERN =
            Pattern.compile("(?:REQ|JIRA|ISSUE|TASK|STORY)[-_:]?\\d{3,}|#\\d{3,}",
                    Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper mapper = new ObjectMapper();

    private final ChangeFileRepository changeFileRepository;
    private final IterationRepository iterationRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AutomationRuleService automationRuleService;
    private final LinkRuleEngineService linkRuleEngine;

    /** Auto code review on every commit — off by default (AI cost). */
    @Value("${evotrace.code-review.auto-enabled:false}")
    private boolean codeReviewAutoEnabled;

    public CommitHandler(ChangeEventRepository changeEventRepository,
                         ChangeFileRepository changeFileRepository,
                         ProjectRepository projectRepository,
                         IterationRepository iterationRepository,
                         KafkaTemplate<String, String> kafkaTemplate,
                         AutomationRuleService automationRuleService,
                         LinkRuleEngineService linkRuleEngine) {
        super(changeEventRepository, projectRepository);
        this.changeFileRepository = changeFileRepository;
        this.iterationRepository = iterationRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.automationRuleService = automationRuleService;
        this.linkRuleEngine = linkRuleEngine;
    }

    @Override
    public EventType supportedType() {
        return EventType.CODE_COMMIT;
    }

    @Override
    protected ChangeEvent buildEvent(Envelope envelope, Long projectId) {
        ChangeEvent event = super.buildEvent(envelope, projectId);
        event.setSummaryStatus("PENDING"); // AI summary task will fill it

        CodeCommitPayload payload = convertPayload(envelope.payload());
        if (payload.message() != null) {
            event.setIterationId(resolveIteration(projectId, payload.message()));
        }
        return event;
    }

    @Override
    protected void postPersist(ChangeEvent event, Envelope envelope) {
        persistFiles(event.getEventId(), convertPayload(envelope.payload()));
        dispatchAiTask(event);
        // 自动化规则：提交事件触发（NOTIFY / CREATE_BUG 等按配置执行）
        try {
            CodeCommitPayload payload = convertPayload(envelope.payload());
            automationRuleService.onEvent(event.getProjectId(), "CHANGE_AI_SUMMARY", Map.of(
                    "eventId", event.getEventId(),
                    "branch", payload.branch() != null ? payload.branch() : "",
                    "commitSha", payload.commitSha() != null ? payload.commitSha() : "",
                    "author", payload.authorName() != null ? payload.authorName() : ""));
        } catch (Exception e) {
            log.warn("automation rule on commit skipped: {}", e.getMessage());
        }
        // Trace v2：对 commit message / branch 应用关联规则（失败不影响主流程）
        try {
            CodeCommitPayload payload = convertPayload(envelope.payload());
            linkRuleEngine.onCommit(event.getProjectId(), event.getEventId(),
                    payload.message(), payload.branch(), payload.authorName());
        } catch (Exception e) {
            log.warn("trace link rule on commit skipped: {}", e.getMessage());
        }
    }

    private void persistFiles(String eventId, CodeCommitPayload payload) {
        if (payload.files() == null) return;
        for (CodeCommitPayload.FileChange fc : payload.files()) {
            ChangeFile file = new ChangeFile();
            file.setEventId(eventId);
            file.setFilePath(fc.newPath() != null ? fc.newPath() : fc.oldPath());
            file.setOldPath(fc.oldPath());
            file.setChangeKind(fc.kind() != null ? fc.kind().name() : "MODIFIED");
            file.setAddLines(fc.addLines());
            file.setDelLines(fc.delLines());
            file.setDiffBlobRef(fc.diffBlobRef());
            changeFileRepository.save(file);
        }
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

            if (codeReviewAutoEnabled) {
                Map<String, Object> reviewTask = Map.of(
                        "taskType", "CODE_REVIEW",
                        "eventId", event.getEventId(),
                        "projectId", event.getProjectId()
                );
                kafkaTemplate.send("evo.tasks.ai", event.getEventId() + "#review",
                        mapper.writeValueAsString(reviewTask));
            }
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
