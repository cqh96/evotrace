package io.evotrace.server.requirement;

import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.ai.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PM 工作台三处 AI 生成（需求扩写 / PRD 初稿 / 原型生成）的统一门控与调用封装。
 * <p>
 * 约定：无可用模型（apiKey 缺失或占位符）或调用失败时返回 {@code null}，
 * 调用方按 {@code generated=false} 降级（返回确定性骨架或提示文案），绝不抛错。
 */
@Service
public class PmAiGateway {

    private static final Logger log = LoggerFactory.getLogger(PmAiGateway.class);

    public static final String TASK_EXPAND = "REQ_EXPAND";
    public static final String TASK_PRD = "PRD_DRAFT";
    public static final String TASK_PROTO = "PROTO_GEN";

    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;

    public PmAiGateway(ModelRouter modelRouter, PromptLoader promptLoader) {
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
    }

    public boolean usable() {
        return modelRouter.hasUsableModel();
    }

    /** 按模板生成并解析为严格 JSON record；无模型/失败返回 null。 */
    public <T> T generate(String taskType, String template, Map<String, String> vars, Class<T> resultClass) {
        if (!modelRouter.hasUsableModel()) {
            log.info("pm-ai: no usable AI model, skipping {}", taskType);
            return null;
        }
        try {
            String prompt = promptLoader.fill(template, vars);
            ChatClient client = modelRouter.clientFor(taskType);
            return client.prompt().user(prompt).call().entity(resultClass);
        } catch (Exception e) {
            log.warn("pm-ai: {} call failed: {}", taskType, e.getMessage());
            return null;
        }
    }

    public String modelName() {
        return modelRouter.getModelName();
    }

    // ==================== 结果结构（严格 JSON record，与提示词模板约定一致） ====================

    /** AI 需求扩写结果。 */
    public record RequirementExpandResult(String businessValue, String userStory,
                                          String acceptanceCriteria, String estimateDays, String techLead) {}

    /** AI PRD 初稿（markdown 内容）。 */
    public record PrdDraftResult(String content) {}

    /** AI 原型生成结果（页面数组，与编辑器 JSON 模型一致）。 */
    public record PrototypeAiResult(List<PrototypePageAi> pages) {}

    public record PrototypePageAi(String id, String name, int width, int height,
                                  List<PrototypeElementAi> elements) {}

    public record PrototypeElementAi(String id, String type, int x, int y, int w, int h,
                                     Map<String, Object> props, String linkTo) {}

    /** 需求扩写输入（表单现状）。 */
    public record ExpandInput(String title, String description, String priority) {}
}
