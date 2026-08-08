package io.evotrace.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.change.ChangeEvent;
import io.evotrace.server.change.ChangeEventRepository;
import io.evotrace.server.change.ChangeFile;
import io.evotrace.server.change.ChangeFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单 MR/PR 描述生成器（借鉴 PR-Agent 的 /describe 工具）。
 * 输入变更事件，AI 生成 PR 标题 + 描述（markdown），落库到 ai_semantic_unit(kind=PR_DESCRIPTION)。
 * 模型不可用或调用失败时回退到基于文件清单的启发式描述。
 */
@Service
public class PrDescriptionGenerator {

    private static final Logger log = LoggerFactory.getLogger(PrDescriptionGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ChangeEventRepository changeEventRepository;
    private final ChangeFileRepository changeFileRepository;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public PrDescriptionGenerator(JdbcTemplate jdbc, ChangeEventRepository changeEventRepository,
                                  ChangeFileRepository changeFileRepository,
                                  ModelRouter modelRouter, PromptLoader promptLoader) {
        this.jdbc = jdbc;
        this.changeEventRepository = changeEventRepository;
        this.changeFileRepository = changeFileRepository;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    /** 生成（含 AI 与启发式回退），并持久化到 ai_semantic_unit。 */
    public Map<String, Object> generate(String eventId) {
        ChangeEvent event = changeEventRepository.findByEventId(eventId).orElse(null);
        if (event == null) {
            return Map.of("error", "change event not found: " + eventId);
        }

        List<ChangeFile> files = changeFileRepository.findByEventId(eventId);
        String changesText = buildChangesText(files);

        PrDescription result = null;
        if (modelRouter.hasUsableModel()) {
            try {
                ChatClient client = modelRouter.clientFor("PR_DESCRIPTION");
                String prompt = promptLoader.fill("pr-description", Map.of(
                        "projectKey", String.valueOf(event.getProjectId()),
                        "branch", event.getBranch() != null ? event.getBranch() : "unknown",
                        "author", event.getAuthor() != null ? event.getAuthor() : "unknown",
                        "fileCount", String.valueOf(files.size()),
                        "changes", changesText));
                result = client.prompt().user(prompt).call().entity(PrDescription.class);
            } catch (Exception e) {
                log.warn("PR description AI call failed, using heuristic: {}", e.getMessage());
            }
        }

        if (result == null || result.title() == null) {
            result = heuristic(event, files);
        }

        persist(event, result);
        return Map.of("eventId", eventId, "title", result.title(), "description", result.description(),
                "fileCount", files.size(), "aiGenerated", result.aiGenerated());
    }

    private String buildChangesText(List<ChangeFile> files) {
        if (files.isEmpty()) {
            return "(no file changes recorded)";
        }
        StringBuilder sb = new StringBuilder();
        for (ChangeFile f : files) {
            sb.append(String.format("- [%s] %s (+%d -%d)%n", f.getChangeKind(), f.getFilePath(),
                    f.getAddLines(), f.getDelLines()));
        }
        return sb.substring(0, Math.min(sb.length(), 4000));
    }

    private PrDescription heuristic(ChangeEvent event, List<ChangeFile> files) {
        String branch = event.getBranch() != null ? event.getBranch() : "main";
        String summary = "本次变更涉及 " + files.size() + " 个文件";
        String title = "[" + branch + "] " + (files.size() > 0 ? "变更 " + files.size() + " 个文件" : "变更");
        String desc = "## 变更内容\n\n" + summary + "。\n\n## 影响范围\n\n" + buildChangesText(files)
                + "\n\n> 由 EvoTrace 自动生成（AI 模型未配置或调用失败）。";
        return new PrDescription(title, desc, false);
    }

    private void persist(ChangeEvent event, PrDescription result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", result.title());
            payload.put("description", result.description());
            payload.put("aiGenerated", result.aiGenerated());
            jdbc.update("""
                    INSERT INTO ai_semantic_unit(project_id, target_type, target_id, kind, content, confidence, created_at)
                    VALUES (?, 'CHANGE_EVENT', ?, 'PR_DESCRIPTION', ?, 0.9, now())
                    ON CONFLICT DO NOTHING
                    """, event.getProjectId(), event.getEventId(), mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("failed to persist PR description", e);
        }
    }

    public record PrDescription(String title, String description, boolean aiGenerated) {
    }
}