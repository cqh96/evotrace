package io.evotrace.server.testplan;

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
 * Test management API (MeterSphere-inspired): test cases + module tree,
 * test plans with execution, unified execution timeline and quality trends.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/testplan")
public class TestPlanController {

    private final JdbcTemplate jdbc;
    private final TestCaseService testCaseService;
    private final TestPlanService testPlanService;
    private final TestExecutionService testExecutionService;
    private final AiTestCaseGenerator aiTestCaseGenerator;

    public TestPlanController(JdbcTemplate jdbc, TestCaseService testCaseService,
                              TestPlanService testPlanService,
                              TestExecutionService testExecutionService,
                              AiTestCaseGenerator aiTestCaseGenerator) {
        this.jdbc = jdbc;
        this.testCaseService = testCaseService;
        this.testPlanService = testPlanService;
        this.testExecutionService = testExecutionService;
        this.aiTestCaseGenerator = aiTestCaseGenerator;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    // ==================== 测试用例 ====================

    @GetMapping("/cases/tree")
    public Result<List<Map<String, Object>>> caseTree(@PathVariable String projectKey) {
        return Result.ok(testCaseService.tree(projectId(projectKey)));
    }

    @GetMapping("/cases")
    public Result<Map<String, Object>> listCases(@PathVariable String projectKey,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String testType,
                                                 @RequestParam(required = false) String priority,
                                                 @RequestParam(required = false) Long requirementId,
                                                 @RequestParam(required = false) Long parentId,
                                                 @RequestParam(required = false) String tag) {
        return Result.ok(testCaseService.list(projectId(projectKey), page, pageSize,
                keyword, testType, priority, requirementId, parentId, tag));
    }

    @GetMapping("/cases/{caseId}")
    public Result<Map<String, Object>> caseDetail(@PathVariable String projectKey,
                                                  @PathVariable Long caseId) {
        return Result.ok(testCaseService.detail(projectId(projectKey), caseId));
    }

    @PostMapping("/cases")
    public Result<Map<String, Object>> createCase(@PathVariable String projectKey,
                                                  @RequestBody Map<String, Object> body) {
        Long id = testCaseService.create(projectId(projectKey), body);
        return Result.ok(Map.of("id", id));
    }

    @PutMapping("/cases/{caseId}")
    public Result<Void> updateCase(@PathVariable String projectKey, @PathVariable Long caseId,
                                   @RequestBody Map<String, Object> body) {
        testCaseService.update(projectId(projectKey), caseId, body);
        return Result.ok(null);
    }

    @DeleteMapping("/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable String projectKey, @PathVariable Long caseId) {
        testCaseService.delete(projectId(projectKey), caseId);
        return Result.ok(null);
    }

    /** AI 生成测试用例建议（对标 MeterSphere 智能用例）：输入变更事件 + 可选需求。 */
    @PostMapping("/cases/ai-generate")
    public Result<Map<String, Object>> aiGenerateCase(@PathVariable String projectKey,
                                                      @RequestBody Map<String, Object> body) {
        String eventId = String.valueOf(body.get("eventId"));
        Long requirementId = body.get("requirementId") != null
                ? ((Number) body.get("requirementId")).longValue() : null;
        return Result.ok(aiTestCaseGenerator.generate(projectId(projectKey), eventId, requirementId));
    }

    /** 需求追溯矩阵：输入需求 → 返回关联用例（含执行状态）、关联缺陷与覆盖度。 */
    @GetMapping("/traceability/requirements/{requirementId}")
    public Result<Map<String, Object>> traceMatrix(@PathVariable String projectKey,
                                                   @PathVariable Long requirementId) {
        return Result.ok(testCaseService.traceMatrix(projectId(projectKey), requirementId));
    }

    @PostMapping("/cases/{caseId}/bugs")
    public Result<Void> linkCaseBug(@PathVariable String projectKey, @PathVariable Long caseId,
                                    @RequestBody Map<String, Object> body) {
        testCaseService.linkBug(projectId(projectKey), caseId,
                ((Number) body.get("bugId")).longValue());
        return Result.ok(null);
    }

    @DeleteMapping("/cases/{caseId}/bugs/{bugId}")
    public Result<Void> unlinkCaseBug(@PathVariable String projectKey, @PathVariable Long caseId,
                                      @PathVariable Long bugId) {
        testCaseService.unlinkBug(projectId(projectKey), caseId, bugId);
        return Result.ok(null);
    }

    // ==================== 测试计划 ====================

    @GetMapping("/plans")
    public Result<List<Map<String, Object>>> listPlans(@PathVariable String projectKey,
                                                       @RequestParam(required = false) String status) {
        return Result.ok(testPlanService.list(projectId(projectKey), status));
    }

    @PostMapping("/plans")
    public Result<Map<String, Object>> createPlan(@PathVariable String projectKey,
                                                  @RequestBody Map<String, Object> body) {
        Long id = testPlanService.create(projectId(projectKey), body);
        return Result.ok(Map.of("id", id));
    }

    @PutMapping("/plans/{planId}")
    public Result<Void> updatePlan(@PathVariable String projectKey, @PathVariable Long planId,
                                   @RequestBody Map<String, Object> body) {
        testPlanService.update(projectId(projectKey), planId, body);
        return Result.ok(null);
    }

    @DeleteMapping("/plans/{planId}")
    public Result<Void> deletePlan(@PathVariable String projectKey, @PathVariable Long planId) {
        testPlanService.delete(projectId(projectKey), planId);
        return Result.ok(null);
    }

    @PutMapping("/plans/{planId}/status")
    public Result<Void> updatePlanStatus(@PathVariable String projectKey, @PathVariable Long planId,
                                         @RequestBody Map<String, Object> body) {
        testPlanService.updateStatus(projectId(projectKey), planId, String.valueOf(body.get("status")));
        return Result.ok(null);
    }

    @GetMapping("/plans/{planId}")
    public Result<Map<String, Object>> planDetail(@PathVariable String projectKey,
                                                  @PathVariable Long planId) {
        return Result.ok(testPlanService.detail(projectId(projectKey), planId));
    }

    @PostMapping("/plans/{planId}/items")
    public Result<Map<String, Object>> addPlanItems(@PathVariable String projectKey,
                                                    @PathVariable Long planId,
                                                    @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("testCaseIds");
        int added = testPlanService.addItems(projectId(projectKey), planId,
                ids.stream().map(Number::longValue).toList());
        return Result.ok(Map.of("added", added));
    }

    @DeleteMapping("/plans/{planId}/items/{itemId}")
    public Result<Void> removePlanItem(@PathVariable String projectKey, @PathVariable Long planId,
                                       @PathVariable Long itemId) {
        testPlanService.removeItem(projectId(projectKey), planId, itemId);
        return Result.ok(null);
    }

    /** 轻量排序：body {direction: UP|DOWN}，对计划项做上移/下移。 */
    @PutMapping("/plans/{planId}/items/{itemId}/reorder")
    public Result<Void> reorderPlanItem(@PathVariable String projectKey, @PathVariable Long planId,
                                        @PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        testPlanService.reorderItem(projectId(projectKey), planId, itemId,
                String.valueOf(body.get("direction")));
        return Result.ok(null);
    }

    @PutMapping("/plans/{planId}/items/{itemId}/execute")
    public Result<Void> executePlanItem(@PathVariable String projectKey, @PathVariable Long planId,
                                        @PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        testPlanService.executeItem(projectId(projectKey), planId, itemId,
                String.valueOf(body.get("status")),
                body.get("executor") != null ? body.get("executor").toString() : null,
                body.get("resultDetail") != null ? body.get("resultDetail").toString() : null);
        return Result.ok(null);
    }

    @GetMapping("/plans/{planId}/report")
    public Result<Map<String, Object>> planReport(@PathVariable String projectKey,
                                                  @PathVariable Long planId) {
        return Result.ok(testPlanService.report(projectId(projectKey), planId));
    }

    /** 推荐 → 计划闭环 */
    @PostMapping("/plans/from-recommendation")
    public Result<Map<String, Object>> planFromRecommendation(@PathVariable String projectKey,
                                                              @RequestBody Map<String, Object> body) {
        return Result.ok(testPlanService.createFromRecommendation(projectId(projectKey),
                String.valueOf(body.get("fromVersion")),
                String.valueOf(body.get("toVersion")),
                body.get("planName") != null ? body.get("planName").toString() : null));
    }

    // ==================== 执行记录与趋势 ====================

    @GetMapping("/executions")
    public Result<Map<String, Object>> listExecutions(@PathVariable String projectKey,
                                                      @RequestParam(required = false) Long releaseId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(testExecutionService.list(projectId(projectKey), releaseId, status,
                from, to, page, pageSize));
    }

    @PostMapping("/executions")
    public Result<Map<String, Object>> recordExecution(@PathVariable String projectKey,
                                                       @RequestBody Map<String, Object> body) {
        Long id = testExecutionService.record(projectId(projectKey), body);
        return Result.ok(Map.of("id", id));
    }

    @GetMapping("/trends/executions")
    public Result<List<Map<String, Object>>> executionTrend(@PathVariable String projectKey,
                                                            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(testExecutionService.executionTrend(projectId(projectKey), days));
    }

    @GetMapping("/trends/bugs")
    public Result<List<Map<String, Object>>> bugTrend(@PathVariable String projectKey,
                                                      @RequestParam(defaultValue = "30") int days) {
        return Result.ok(testExecutionService.bugTrend(projectId(projectKey), days));
    }
}
