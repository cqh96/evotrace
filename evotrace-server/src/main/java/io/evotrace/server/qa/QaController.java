package io.evotrace.server.qa;

import io.evotrace.common.Result;
import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.ai.PromptLoader;
import io.evotrace.server.ai.config.AiModelConfig;
import io.evotrace.server.ai.config.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evolution Q&A: keyword retrieval over AI summaries, then LLM answer generation
 * via the configured model (default model, or a specific config chosen on the page).
 * Falls back to a keyword-only answer when no model is usable or the call fails.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/qa")
public class QaController {

    private static final Logger log = LoggerFactory.getLogger(QaController.class);

    private final JdbcTemplate jdbcTemplate;
    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final AiModelConfigRepository configRepository;

    public QaController(JdbcTemplate jdbcTemplate, ModelRouter modelRouter,
                        PromptLoader promptLoader, AiModelConfigRepository configRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
        this.configRepository = configRepository;
    }

    public record AskRequest(String question, Long modelId) {
    }

    @PostMapping
    public Result<Map<String, Object>> ask(@PathVariable String projectKey, @RequestBody AskRequest request) {
        List<String> keywords = buildKeywords(request.question());
        String where = keywords.stream().map(k -> "s.content ILIKE ?").reduce((a, b) -> a + " OR " + b)
                .orElse("1=0");
        List<Object> params = new ArrayList<>();
        params.add(projectKey);
        keywords.forEach(k -> params.add("%" + k + "%"));
        List<Map<String, Object>> hits = jdbcTemplate.queryForList("""
                SELECT c.event_id AS id, c.event_type AS type, c.commit_sha AS sha, s.content AS title
                FROM change_event c
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id
                WHERE %s ORDER BY c.occurred_at DESC LIMIT 5
                """.formatted(where), params.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("references", hits);

        // 模型可用性: 显式选择的配置(启用且有 key)优先,否则看默认模型
        AiModelConfig chosen = null;
        if (request.modelId() != null) {
            chosen = configRepository.findById(request.modelId())
                    .filter(AiModelConfig::isEnabled)
                    .orElse(null);
        }
        boolean canUseAi = chosen != null
                ? chosen.getApiKey() != null && !chosen.getApiKey().isBlank()
                : modelRouter.hasUsableModel();

        if (canUseAi) {
            try {
                ChatClient client = modelRouter.clientForConfig(request.modelId());
                String refs = buildRefsText(hits);
                String prompt = promptLoader.fill("qa", Map.of(
                        "question", request.question(),
                        "references", refs));
                String answer = client.prompt().user(prompt).call().content();
                if (answer != null && !answer.isBlank()) {
                    result.put("answer", answer);
                    result.put("model", chosen != null ? chosen.getModelName() : modelRouter.getModelName());
                    return Result.ok(result);
                }
            } catch (Exception e) {
                log.warn("QA LLM call failed, falling back to keyword answer: {}", e.getMessage());
            }
        }

        String answer = hits.isEmpty()
                ? "未在演化记录中检索到与问题相关的变更。可尝试调整关键词,或在「AI 模型配置」中启用带 API Key 的模型获得语义化回答。"
                : "基于关键字检索到 " + hits.size() + " 条相关变更,见下方引用。配置可用模型后可获得语义化推理回答。";
        result.put("answer", answer);
        return Result.ok(result);
    }

    /**
     * 朴素中文关键词抽取:去掉标点后取 2-gram + 整句,去重限长。
     * 中文无空格分词,整句 ILIKE 几乎无法命中,拆成 2-gram OR 匹配更可靠。
     */
    private List<String> buildKeywords(String question) {
        Set<String> grams = new LinkedHashSet<>();
        String cleaned = question.replaceAll("[\\s\\p{Punct}]+", "");
        if (cleaned.length() < 2) {
            return List.of();
        }
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            String g = cleaned.substring(i, i + 2);
            if (Character.isLetterOrDigit(g.charAt(0)) && Character.isLetterOrDigit(g.charAt(1))) {
                grams.add(g);
            }
        }
        if (cleaned.length() >= 4) {
            grams.add(cleaned);   // 完整片段优先命中
        }
        List<String> result = new ArrayList<>(grams);
        return result.size() > 20 ? result.subList(0, 20) : result;
    }

    private String buildRefsText(List<Map<String, Object>> hits) {
        if (hits.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> h : hits) {
            sb.append("- [").append(h.get("type")).append("]");
            if (h.get("sha") != null) {
                sb.append(" `").append(h.get("sha")).append("`");
            }
            if (h.get("title") != null) {
                sb.append(": ").append(h.get("title"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
