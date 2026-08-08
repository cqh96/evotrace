package io.evotrace.server.requirement;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * PM 需求工作台：文档版本链 / 原型 / 任务拆分 / 生命周期 / AI 生成。
 * 沿用 PMDashboardController 的 @RequestParam projectKey + JdbcTemplate 查 projectId 惯例。
 */
@RestController
@RequestMapping("/api/v1/pm")
public class RequirementWorkbenchController {

    // 代码库惯例：Jackson 2 ObjectMapper 无 Spring bean
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;
    private final RequirementService requirementService;
    private final RequirementDocumentService documentService;
    private final RequirementPrototypeService prototypeService;
    private final RequirementTaskService taskService;
    private final LifecycleService lifecycleService;
    private final PmAiGateway aiGateway;

    public RequirementWorkbenchController(JdbcTemplate jdbc, RequirementService requirementService,
                                          RequirementDocumentService documentService,
                                          RequirementPrototypeService prototypeService,
                                          RequirementTaskService taskService,
                                          LifecycleService lifecycleService,
                                          PmAiGateway aiGateway) {
        this.jdbc = jdbc;
        this.requirementService = requirementService;
        this.documentService = documentService;
        this.prototypeService = prototypeService;
        this.taskService = taskService;
        this.lifecycleService = lifecycleService;
        this.aiGateway = aiGateway;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 校验需求存在且属于该项目，返回 requirementId。 */
    private Long checkRequirement(Long projectId, Long requirementId) {
        Long pid = jdbc.queryForObject(
                "SELECT project_id FROM requirement WHERE id = ?", Long.class, requirementId);
        if (!projectId.equals(pid)) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        return requirementId;
    }

    private static String actor(String fallback) {
        String current = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication().getName()
                : null;
        return current != null && !current.isBlank() ? current : fallback;
    }

    // ==================== AI 生成 ====================

    /** AI 需求扩写：一句话/草稿 → 结构化建模字段建议稿（不落库，回填表单）。 */
    @PostMapping("/requirements/ai-expand")
    public Result<Map<String, Object>> aiExpand(@RequestParam String projectKey,
                                                @RequestBody PmAiGateway.ExpandInput input) {
        if (!aiGateway.usable()) {
            return Result.ok(Map.of("generated", false, "message",
                    "未配置可用 AI 模型（apiKey 缺失或为占位符），请在「AI 模型配置」启用后重试",
                    "businessValue", "", "userStory", "", "acceptanceCriteria", "", "estimateDays", "", "techLead", ""));
        }
        PmAiGateway.RequirementExpandResult result = aiGateway.generate(
                PmAiGateway.TASK_EXPAND, "requirement-expand", Map.of(
                        "title", input.title() == null ? "" : input.title(),
                        "description", input.description() == null ? "" : input.description(),
                        "priority", input.priority() == null ? "" : input.priority()),
                PmAiGateway.RequirementExpandResult.class);
        if (result == null) {
            return Result.ok(Map.of("generated", false, "message", "AI 生成失败，请重试",
                    "businessValue", "", "userStory", "", "acceptanceCriteria", "", "estimateDays", "", "techLead", ""));
        }
        return Result.ok(Map.of("generated", true, "message", "", "model", aiGateway.modelName(),
                "businessValue", orEmpty(result.businessValue()), "userStory", orEmpty(result.userStory()),
                "acceptanceCriteria", orEmpty(result.acceptanceCriteria()),
                "estimateDays", orEmpty(result.estimateDays()), "techLead", orEmpty(result.techLead())));
    }

    // ==================== 需求溯源 ====================

    /** 需求详情：全行字段 + 文档/任务/原型派生信息（抽屉详情用）。 */
    @GetMapping("/requirements/{id}/detail")
    public Result<Map<String, Object>> detail(@RequestParam String projectKey, @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(requirementService.detail(projectId, id));
    }

    /** 需求全链路溯源：变更/用例/缺陷/发布（补上已实现未暴露的 trace）。 */
    @GetMapping("/requirements/{id}/trace")
    public Result<Map<String, Object>> trace(@RequestParam String projectKey, @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(requirementService.trace(projectId, id));
    }

    // ==================== 文档 ====================

    @GetMapping("/requirements/{id}/document")
    public Result<Map<String, Object>> documentLatest(@RequestParam String projectKey, @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(documentService.latest(id));
    }

    @GetMapping("/requirements/{id}/document/versions")
    public Result<List<Map<String, Object>>> documentVersions(@RequestParam String projectKey,
                                                              @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(documentService.versions(id));
    }

    @GetMapping("/requirements/{id}/document/versions/{version}")
    public Result<Map<String, Object>> documentVersion(@RequestParam String projectKey,
                                                       @PathVariable Long id,
                                                       @PathVariable int version) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(documentService.version(id, version));
    }

    /** 保存为新版本（body: {title?, content}）。 */
    @PostMapping("/requirements/{id}/document")
    public Result<Map<String, Object>> documentSave(@RequestParam String projectKey,
                                                    @PathVariable Long id,
                                                    @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(documentService.save(id,
                body.get("title") != null ? body.get("title").toString() : null,
                body.get("content") != null ? body.get("content").toString() : null,
                actor("PM")));
    }

    /** 回滚：复制旧版另存为新版本（body: {version}）。 */
    @PostMapping("/requirements/{id}/document/rollback")
    public Result<Map<String, Object>> documentRollback(@RequestParam String projectKey,
                                                        @PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        Object versionObj = body.get("version");
        if (versionObj == null) {
            throw new IllegalArgumentException("缺少回滚目标版本");
        }
        return Result.ok(documentService.rollback(id, ((Number) versionObj).intValue(), actor("PM")));
    }

    /** AI 生成 PRD 初稿（不落库，body: {prompt?} 覆盖描述）。 */
    @PostMapping("/requirements/{id}/document/ai-draft")
    public Result<Map<String, Object>> documentAiDraft(@RequestParam String projectKey,
                                                       @PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        Map<String, Object> reqRow = jdbc.queryForMap(
                "SELECT title, description, user_story, business_value FROM requirement WHERE id = ?", id);
        String prompt = body != null && body.get("prompt") != null ? body.get("prompt").toString() : null;
        return Result.ok(documentService.aiDraft(id, reqRow, prompt));
    }

    // ==================== 原型 ====================

    @GetMapping("/requirements/{id}/prototype")
    public Result<Map<String, Object>> prototypeGet(@RequestParam String projectKey, @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(prototypeService.get(id));
    }

    /** 保存原型（body: {pages: [...]}）。 */
    @PutMapping("/requirements/{id}/prototype")
    public Result<Map<String, Object>> prototypeSave(@RequestParam String projectKey,
                                                     @PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        Object pages = body.get("pages");
        if (!(pages instanceof List<?>)) {
            throw new IllegalArgumentException("原型 pages 必须是数组");
        }
        try {
            String json = MAPPER.writeValueAsString(pages);
            return Result.ok(prototypeService.save(id, json, actor("PM")));
        } catch (Exception e) {
            throw new IllegalArgumentException("原型 JSON 解析失败");
        }
    }

    /** AI 生成原型（body: {prompt?}）。 */
    @PostMapping("/requirements/{id}/prototype/ai-generate")
    public Result<Map<String, Object>> prototypeAiGenerate(@RequestParam String projectKey,
                                                           @PathVariable Long id,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        String title = jdbc.queryForObject(
                "SELECT title FROM requirement WHERE id = ?", String.class, id);
        String prompt = body != null && body.get("prompt") != null ? body.get("prompt").toString() : "";
        return Result.ok(prototypeService.aiGenerate(id, title, prompt));
    }

    // ==================== 任务 ====================

    @GetMapping("/requirements/{id}/tasks")
    public Result<List<Map<String, Object>>> tasks(@RequestParam String projectKey, @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(taskService.list(id));
    }

    @PostMapping("/requirements/{id}/tasks")
    public Result<Map<String, Object>> taskCreate(@RequestParam String projectKey,
                                                  @PathVariable Long id,
                                                  @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(taskService.create(id, body));
    }

    @PutMapping("/requirements/{id}/tasks/reorder")
    public Result<Void> taskReorder(@RequestParam String projectKey,
                                    @PathVariable Long id,
                                    @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        Object order = body.get("order");
        if (!(order instanceof List<?> list)) {
            throw new IllegalArgumentException("order 必须是数组");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orderList = (List<Map<String, Object>>) (List<?>) list;
        taskService.reorder(id, orderList);
        return Result.ok(null);
    }

    @PutMapping("/requirements/{id}/tasks/{taskId}")
    public Result<Void> taskUpdate(@RequestParam String projectKey,
                                   @PathVariable Long id, @PathVariable Long taskId,
                                   @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        taskService.update(id, taskId, body);
        return Result.ok(null);
    }

    @PutMapping("/requirements/{id}/tasks/{taskId}/status")
    public Result<Void> taskStatus(@RequestParam String projectKey,
                                   @PathVariable Long id, @PathVariable Long taskId,
                                   @RequestBody Map<String, Object> body) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        Object status = body.get("status");
        if (status == null) {
            throw new IllegalArgumentException("缺少 status");
        }
        taskService.updateStatus(id, taskId, status.toString());
        return Result.ok(null);
    }

    @DeleteMapping("/requirements/{id}/tasks/{taskId}")
    public Result<Void> taskDelete(@RequestParam String projectKey,
                                   @PathVariable Long id, @PathVariable Long taskId) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        taskService.delete(id, taskId);
        return Result.ok(null);
    }

    // ==================== 生命周期 ====================

    /** 版本路线图：已发布 release + 未发布 target_version 聚合。 */
    @GetMapping("/lifecycle/roadmap")
    public Result<List<Map<String, Object>>> roadmap(@RequestParam String projectKey) {
        return Result.ok(lifecycleService.roadmap(projectId(projectKey)));
    }

    /** 状态流转审计：各状态停留时长 + 30 天流转矩阵/趋势 + 平均周期。 */
    @GetMapping("/lifecycle/status-flow")
    public Result<Map<String, Object>> statusFlow(@RequestParam String projectKey) {
        return Result.ok(lifecycleService.statusFlow(projectId(projectKey)));
    }

    /** 单需求状态序列（含各阶段停留天数）。 */
    @GetMapping("/requirements/{id}/status-history")
    public Result<List<Map<String, Object>>> statusHistory(@RequestParam String projectKey,
                                                           @PathVariable Long id) {
        Long projectId = projectId(projectKey);
        checkRequirement(projectId, id);
        return Result.ok(lifecycleService.statusHistory(id));
    }

    private static String orEmpty(String v) {
        return v != null ? v : "";
    }
}
