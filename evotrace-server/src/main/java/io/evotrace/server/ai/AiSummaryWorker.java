package io.evotrace.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.change.ChangeEvent;
import io.evotrace.server.change.ChangeEventRepository;
import io.evotrace.server.change.ChangeFile;
import io.evotrace.server.change.ChangeFileRepository;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Async AI analysis worker. Consumes evo.tasks.ai and produces
 * SemanticUnit records via Spring AI ChatClient (structured output).
 * All calls run on virtual threads; failures stay in Kafka and are
 * retried with exponential backoff before landing in the DLT.
 */
@Component
public class AiSummaryWorker {

    public static final String TOPIC_AI_TASKS = "evo.tasks.ai";

    private static final Logger log = LoggerFactory.getLogger(AiSummaryWorker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final ChangeEventRepository changeEventRepository;
    private final ChangeFileRepository changeFileRepository;
    private final ProjectRepository projectRepository;
    private final AiSemanticUnitRepository semanticUnitRepository;
    private final CodeReviewEngine codeReviewEngine;

    @Value("${evotrace.ai.privacy-default:STRUCTURE_ONLY}")
    private String privacyDefault;

    public AiSummaryWorker(ModelRouter modelRouter,
                           PromptLoader promptLoader,
                           ChangeEventRepository changeEventRepository,
                           ChangeFileRepository changeFileRepository,
                           ProjectRepository projectRepository,
                           AiSemanticUnitRepository semanticUnitRepository,
                           CodeReviewEngine codeReviewEngine) {
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
        this.changeEventRepository = changeEventRepository;
        this.changeFileRepository = changeFileRepository;
        this.projectRepository = projectRepository;
        this.semanticUnitRepository = semanticUnitRepository;
        this.codeReviewEngine = codeReviewEngine;
    }

    @KafkaListener(topics = TOPIC_AI_TASKS, groupId = "ai-analysis",
            containerFactory = "aiTaskListenerContainerFactory")
    public void onTask(String taskJson) {
        log.info("ai task received: {}", taskJson);
        try {
            AiTaskPayload task = mapper.readValue(taskJson, AiTaskPayload.class);
            if ("SUMMARIZE".equals(task.taskType())) {
                generateSummary(task);
            } else if ("CODE_REVIEW".equals(task.taskType())) {
                codeReviewEngine.review(task.eventId(), "AUTO");
            }
        } catch (Exception e) {
            log.error("failed to process AI task: {}", taskJson, e);
            // Unexpected failures (parse errors, DB errors) propagate to the
            // Kafka error handler: retry with backoff, then DLT after 3 attempts.
            // Business failures inside generateSummary (AI call failed → FAILED)
            // are handled internally and are not rethrown.
            throw new RuntimeException("AI task processing failed", e);
        }
    }

    private void generateSummary(AiTaskPayload task) {
        ChangeEvent event = changeEventRepository.findByEventId(task.eventId()).orElse(null);
        if (event == null) {
            log.warn("change event not found: {}", task.eventId());
            return;
        }

        Project project = projectRepository.findById(event.getProjectId()).orElse(null);
        if (project == null) {
            log.warn("project not found: {}", event.getProjectId());
            return;
        }

        // Build diff context from changed files
        List<ChangeFile> files = changeFileRepository.findByEventId(event.getEventId());
        StringBuilder diffBuilder = new StringBuilder();
        for (ChangeFile f : files) {
            diffBuilder.append(String.format("  [%s] %s (+%d -%d lines)\n",
                    f.getChangeKind(), f.getFilePath(), f.getAddLines(), f.getDelLines()));
        }
        String diff = !diffBuilder.isEmpty() ? diffBuilder.toString() : "(no file-level diff available)";

        // Fill prompt template
        String prompt = promptLoader.fill("change-summary", Map.of(
                "projectKey", project.getProjectKey(),
                "appKey", event.getEventType(),
                "iterationTitle", event.getIterationId() != null ? event.getIterationId().toString() : "N/A",
                "similarSummaries", "(none)",
                "diff", diff
        ));

        // Call AI model for structured output
        ChatClient client = modelRouter.clientFor("SUMMARIZE");
        ChangeSummaryResult result;
        try {
            result = client.prompt()
                    .user(prompt)
                    .call()
                    .entity(ChangeSummaryResult.class);
        } catch (Exception e) {
            log.error("AI call failed for event {}: {}", event.getEventId(), e.getMessage());
            // Mark as failed so it can be retried
            event.setSummaryStatus("FAILED");
            changeEventRepository.save(event);
            return;
        }

        if (result == null) {
            log.warn("AI returned null result for event {}", event.getEventId());
            event.setSummaryStatus("FAILED");
            changeEventRepository.save(event);
            return;
        }

        // Persist AI semantic unit
        AiSemanticUnit unit = new AiSemanticUnit();
        unit.setTargetType("CHANGE_EVENT");
        unit.setTargetId(event.getEventId());
        unit.setKind("SUMMARY");
        unit.setContent(result.summary());
        unit.setModel(modelRouter.getModelName());
        unit.setConfidence(result.confidence() != null ? result.confidence() : BigDecimal.valueOf(0.5));
        semanticUnitRepository.save(unit);

        // Update event status
        event.setSummaryStatus("DONE");
        changeEventRepository.save(event);

        log.info("AI summary generated for event {}: category={} confidence={}",
                event.getEventId(), result.changeCategory(), result.confidence());
    }
}
