package io.evotrace.server.requirement;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 需求文档解析：材料文本 → 结构化需求列表 + 用例建议。
 * 门控沿用 {@link PmAiGateway}：无模型/调用失败返回 null，调用方降级处理。
 */
@Service
public class RequirementDocParser {

    public static final String TASK_DOC_PARSE = "REQ_DOC_PARSE";
    public static final String TEMPLATE = "requirement-doc-parse";

    private final PmAiGateway aiGateway;

    public RequirementDocParser(PmAiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    public boolean usable() {
        return aiGateway.usable();
    }

    public String modelName() {
        return aiGateway.modelName();
    }

    /** 解析材料文本；无模型/失败返回 null。 */
    public ParsedDoc parse(String sourceType, String sourceName, String content) {
        return aiGateway.generate(TASK_DOC_PARSE, TEMPLATE, Map.of(
                "sourceType", sourceType != null ? sourceType : "",
                "sourceName", sourceName != null ? sourceName : "",
                "content", content != null ? content : ""), ParsedDoc.class);
    }

    // ==================== 结果结构（与 requirement-doc-parse.st 约定一致） ====================

    public record ParsedDoc(String docTitle, List<ParsedRequirement> requirements) {}

    public record ParsedRequirement(String title, String userStory, String acceptanceCriteria,
                                    String priority, String businessValue,
                                    List<ParsedCase> suggestedCases) {}

    public record ParsedCase(String title, String testType, String priority, String steps) {}
}
