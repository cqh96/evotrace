package io.evotrace.server.testplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.api.ApiRepository;
import io.evotrace.server.api.ApiScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side test execution engine (API 型执行器).
 * <p>
 * Executes the {@code http} step type of a test case via {@link HttpClient} and
 * evaluates per-step assertions (statusCode / bodyContains / bodyNotContains /
 * responseTimeMs). Browser-UI steps (open/click/...) cannot be executed here —
 * such cases are non-runnable and must be executed manually.
 * <p>
 * Results are persisted through {@link TestExecutionService#record} — the same
 * write path used by external runners via the open API — so executions show up
 * in 执行记录 and feed the quality gate without any downstream change.
 */
@Service
public class TestExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionRunner.class);

    private static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    // 代码库惯例：Jackson 2 ObjectMapper 无 Spring bean（Spring Boot 4 自带的是 Jackson 3），
    // 与 CommitHandler/WebhookController 一致使用静态实例。
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TestExecutionService executionService;
    private final AssertionEvaluator assertionEvaluator;
    private final ApiScenarioService scenarioService;
    private final ApiRepository apiRepository;
    private final HttpClient httpClient;
    private final long stepTimeoutMs;
    private final int maxResponseChars;
    private final int maxSteps;

    public TestExecutionRunner(JdbcTemplate jdbc, TestExecutionService executionService,
                               AssertionEvaluator assertionEvaluator,
                               ApiScenarioService scenarioService,
                               ApiRepository apiRepository,
                               @Value("${evotrace.test-executor.request-timeout-ms:8000}") long stepTimeoutMs,
                               @Value("${evotrace.test-executor.insecure-tls:false}") boolean insecureTls,
                               @Value("${evotrace.test-executor.max-response-chars:2000}") int maxResponseChars,
                               @Value("${evotrace.test-executor.max-steps:20}") int maxSteps) {
        this.jdbc = jdbc;
        this.executionService = executionService;
        this.assertionEvaluator = assertionEvaluator;
        this.scenarioService = scenarioService;
        this.apiRepository = apiRepository;
        this.stepTimeoutMs = stepTimeoutMs;
        this.maxResponseChars = maxResponseChars;
        this.maxSteps = maxSteps;
        this.httpClient = buildHttpClient(insecureTls);
    }

    /** 用例可执行判定：steps 非空且全部为 http 步骤（浏览器步骤需手动执行）。 */
    public static boolean isRunnable(String stepsJson) {
        return isRunnableSteps(parseSteps(stepsJson));
    }

    /** 解析步骤 JSON（失败或空返回空列表，执行与可执行判定共用）。 */
    public static List<Map<String, Object>> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = mapper.readValue(stepsJson, Object.class);
            if (parsed instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> steps = (List<Map<String, Object>>) (List<?>) list;
                return steps;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("用例步骤 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isRunnableSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return steps.stream().allMatch(s -> "http".equals(s.get("action")));
    }

    /** 执行单个用例：校验归属与可执行性 → 逐步执行 → 持久化 → 返回含 executionId 的结果。 */
    public Map<String, Object> runCase(Long projectId, Long caseId, Map<String, Object> req) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT tc.id, tc.project_id AS "projectId", tc.title, tc.steps
                FROM test_case tc WHERE tc.id = ? AND tc.project_id = ?
                """, caseId, projectId);
        List<Map<String, Object>> steps = parseSteps((String) row.get("steps"));
        if (!isRunnableSteps(steps)) {
            throw new IllegalArgumentException("用例含浏览器 UI 步骤，无法在服务端执行");
        }
        if (steps.size() > maxSteps) {
            throw new IllegalArgumentException("用例步骤数超过上限 " + maxSteps + "，无法在服务端执行");
        }

        CaseRun run = executeSteps(steps, null);
        Map<String, Object> result = buildCaseResult(row, run);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("testCaseId", caseId);
        data.put("status", run.verdict());
        data.put("executor", currentUser());
        data.put("resultDetail", writeJson(result));
        data.put("releaseId", req != null ? req.get("releaseId") : null);
        Long executionId = executionService.record(projectId, data);
        result.put("executionId", executionId);
        log.info("case executed: project={} caseId={} verdict={}", projectId, caseId, run.verdict());
        return result;
    }

    /** 执行整个计划：DRAFT 自动转 RUNNING，逐项执行（不可执行标记 SKIPPED 带原因）。 */
    public Map<String, Object> runPlan(Long projectId, Long planId, Map<String, Object> req) {
        Map<String, Object> plan = jdbc.queryForMap(
                "SELECT id, name, status, environment_id AS \"environmentId\" FROM test_plan WHERE id = ? AND project_id = ?",
                planId, projectId);
        if ("DONE".equals(plan.get("status"))) {
            throw new IllegalArgumentException("已完成计划不可再执行");
        }
        if ("DRAFT".equals(plan.get("status"))) {
            jdbc.update("UPDATE test_plan SET status = 'RUNNING', updated_at = now() WHERE id = ?", planId);
        }
        Long environmentId = plan.get("environmentId") != null
                ? ((Number) plan.get("environmentId")).longValue() : null;

        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT pi.id AS "itemId", pi.test_case_id AS "testCaseId", pi.item_type AS "itemType",
                       pi.scenario_id AS "scenarioId", pi.sort_order AS "sortOrder", tc.title, tc.steps
                FROM test_plan_item pi LEFT JOIN test_case tc ON tc.id = pi.test_case_id
                WHERE pi.plan_id = ? ORDER BY pi.sort_order, pi.id
                """, planId);

        long started = System.nanoTime();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        for (Map<String, Object> item : items) {
            Long itemId = ((Number) item.get("itemId")).longValue();
            String itemType = String.valueOf(item.getOrDefault("itemType", "CASE"));
            String title = item.get("title") != null ? String.valueOf(item.get("title")) : "场景";

            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("itemId", itemId);
            itemResult.put("itemType", itemType);
            itemResult.put("title", title);

            Map<String, Object> data = new LinkedHashMap<>();
            if ("SCENARIO".equals(itemType)) {
                Long scenarioId = ((Number) item.get("scenarioId")).longValue();
                Map<String, Object> scResult = (Map<String, Object>) scenarioService.run(projectId, scenarioId, environmentId, null);
                String verdict = String.valueOf(scResult.getOrDefault("verdict", "FAILED"));
                if ("PASSED".equals(verdict)) passed++; else failed++;
                itemResult.put("scenarioId", scenarioId);
                itemResult.put("verdict", verdict);
                itemResult.put("durationMs", scResult.get("durationMs"));
                itemResult.put("steps", scResult.get("steps"));
                data.put("testCaseId", null);
                data.put("executor", currentUser());
                data.put("status", verdict);
                data.put("resultDetail", writeJson(itemResult));
                executionService.record(projectId, data);
                results.add(itemResult);
                continue;
            }

            Long testCaseId = ((Number) item.get("testCaseId")).longValue();
            itemResult.put("testCaseId", testCaseId);
            List<Map<String, Object>> steps = parseSteps((String) item.get("steps"));
            data.put("testCaseId", testCaseId);
            data.put("planItemId", itemId);
            data.put("executor", currentUser());

            if (!isRunnableSteps(steps)) {
                skipped++;
                itemResult.put("verdict", "SKIPPED");
                itemResult.put("reason", "含浏览器 UI 步骤，无法在服务端执行");
                itemResult.put("durationMs", 0);
                itemResult.put("steps", List.of());
                data.put("status", "SKIPPED");
                data.put("resultDetail", writeJson(itemResult));
            } else if (steps.size() > maxSteps) {
                skipped++;
                itemResult.put("verdict", "SKIPPED");
                itemResult.put("reason", "步骤数超过上限 " + maxSteps + "，无法在服务端执行");
                itemResult.put("durationMs", 0);
                itemResult.put("steps", List.of());
                data.put("status", "SKIPPED");
                data.put("resultDetail", writeJson(itemResult));
            } else {
                CaseRun run = executeSteps(steps, environmentId);
                if ("PASSED".equals(run.verdict())) passed++;
                else failed++;
                Map<String, Object> caseResult = new LinkedHashMap<>();
                caseResult.put("caseId", testCaseId);
                caseResult.put("title", title);
                caseResult.put("runnable", true);
                caseResult.put("verdict", run.verdict());
                caseResult.put("durationMs", run.durationMs());
                caseResult.put("summary", run.summary());
                caseResult.put("steps", run.steps());
                data.put("status", run.verdict());
                data.put("resultDetail", writeJson(caseResult));
                itemResult.putAll(caseResult);
            }
            executionService.record(projectId, data);
            results.add(itemResult);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("planName", plan.get("name"));
        result.put("total", items.size());
        result.put("runnable", items.size() - skipped);
        result.put("skipped", skipped);
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        result.put("results", results);
        log.info("plan executed: project={} planId={} passed={} failed={} skipped={}",
                projectId, planId, passed, failed, skipped);
        return result;
    }

    // ==================== 执行引擎 ====================

    /** 逐步执行（fail-fast：首败即停，剩余步骤标 SKIPPED）。 */
    private CaseRun executeSteps(List<Map<String, Object>> steps, Long environmentId) {
        Map<String, Object> env = buildEnvContext(environmentId);
        long started = System.nanoTime();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        String verdict = "PASSED";
        int passedCount = 0;
        int total = steps.size();
        for (int i = 0; i < total; i++) {
            if (!"PASSED".equals(verdict)) {
                stepResults.add(skippedStep(steps.get(i), i, "上一步失败，跳过"));
                continue;
            }
            Map<String, Object> stepResult;
            try {
                stepResult = executeHttpStep(steps.get(i), i, env);
            } catch (Exception e) {
                log.warn("step execution error: index={} error={}", i, e.getMessage());
                stepResult = failedStep(steps.get(i), i, e.getMessage());
            }
            if ("PASSED".equals(stepResult.get("status"))) {
                passedCount++;
            } else {
                verdict = "FAILED";
            }
            stepResults.add(stepResult);
        }
        return new CaseRun(verdict, (System.nanoTime() - started) / 1_000_000,
                passedCount + "/" + total + " 步骤通过", stepResults);
    }

    /** 从环境构建变量替换上下文：baseUrl + 全局变量；无环境时为空。 */
    private Map<String, Object> buildEnvContext(Long environmentId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (environmentId == null) return ctx;
        ApiRepository.Environment env = apiRepository.findEnvironment(environmentId);
        if (env == null) return ctx;
        if (env.baseUrl() != null) ctx.put("baseUrl", env.baseUrl());
        if (env.variables() != null) ctx.putAll(env.variables());
        return ctx;
    }

    /** ${var} 替换。 */
    private String substitute(String text, Map<String, Object> ctx) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{(\\w+)}").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = ctx.get(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v != null ? String.valueOf(v) : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeHttpStep(Map<String, Object> step, int index, Map<String, Object> env) throws Exception {
        String method = String.valueOf(step.getOrDefault("method", "GET")).toUpperCase();
        if (!HTTP_METHODS.contains(method)) {
            throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        }
        String url = substitute(String.valueOf(step.getOrDefault("url", "")).trim(), env);
        if (url.isBlank()) {
            throw new IllegalArgumentException("步骤缺少 url");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("仅支持 http/https 目标地址: " + url);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(stepTimeoutMs));
        Map<String, Object> headers = (Map<String, Object>) step.getOrDefault("headers", Map.of());
        for (Map.Entry<String, Object> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                rb.header(e.getKey(), substitute(String.valueOf(e.getValue()), env));
            }
        }
        String body = step.get("body") != null ? substitute(String.valueOf(step.get("body")), env) : "";
        boolean hasBody = !body.isBlank();
        // 请求体存在且未显式声明 Content-Type 时默认 JSON
        boolean hasContentType = headers.keySet().stream().anyMatch(k -> "content-type".equalsIgnoreCase(String.valueOf(k)));
        if (hasBody && !hasContentType) {
            rb.header("Content-Type", "application/json");
        }
        rb.method(method, hasBody ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

        long start = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + e.getMessage());
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String responseBody = resp.body() != null ? resp.body() : "";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("name", step.get("name"));
        result.put("type", "http");
        result.put("method", method);
        result.put("url", url);
        result.put("statusCode", resp.statusCode());
        result.put("durationMs", durationMs);
        result.put("responseSnippet", truncate(responseBody));
        result.put("assertions", assertionEvaluator.evaluate(
                (List<Map<String, Object>>) step.getOrDefault("assertions", List.of()),
                resp.statusCode(), responseBody, durationMs));

        List<?> assertionResults = (List<?>) result.get("assertions");
        boolean allPassed = assertionResults.stream()
                .allMatch(a -> Boolean.TRUE.equals(((Map<?, ?>) a).get("passed")));
        // 有断言时以断言为唯一标准（状态码需显式断言）；无断言时以 2xx 兜底
        boolean statusOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
        boolean passed = assertionResults.isEmpty() ? statusOk : allPassed;
        String status = passed ? "PASSED" : "FAILED";
        result.put("status", status);
        if (!"PASSED".equals(status)) {
            List<String> reasons = new ArrayList<>();
            for (Object a : assertionResults) {
                Map<?, ?> m = (Map<?, ?>) a;
                if (!Boolean.TRUE.equals(m.get("passed"))) {
                    reasons.add(String.valueOf(m.get("message")));
                }
            }
            if (reasons.isEmpty() && !statusOk) {
                reasons.add("状态码 " + resp.statusCode() + " 非 2xx");
            }
            result.put("error", String.join("；", reasons));
        }
        return result;
    }

    // ==================== 结果构造 ====================

    private Map<String, Object> buildCaseResult(Map<String, Object> row, CaseRun run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", row.get("id"));
        result.put("title", row.get("title"));
        result.put("runnable", true);
        result.put("verdict", run.verdict());
        result.put("durationMs", run.durationMs());
        result.put("summary", run.summary());
        result.put("steps", run.steps());
        return result;
    }

    private Map<String, Object> skippedStep(Map<String, Object> step, int index, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("name", step.get("name"));
        r.put("type", step.get("action"));
        r.put("status", "SKIPPED");
        r.put("durationMs", 0);
        r.put("error", reason);
        return r;
    }

    private Map<String, Object> failedStep(Map<String, Object> step, int index, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("name", step.get("name"));
        r.put("type", step.get("action"));
        r.put("status", "FAILED");
        r.put("durationMs", 0);
        r.put("error", error);
        return r;
    }

    private String truncate(String body) {
        if (body == null) return "";
        if (body.length() <= maxResponseChars) return body;
        return body.substring(0, maxResponseChars) + "…(已截断)";
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("结果序列化失败", e);
        }
    }

    /** 当前登录用户名（JWT 主体），未登录时记 server。 */
    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()
                ? auth.getName() : "server";
    }

    private static HttpClient buildHttpClient(boolean insecureTls) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3));
        if (insecureTls) {
            try {
                TrustManager[] trustAll = {new X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAll, new SecureRandom());
                builder.sslContext(ctx);
            } catch (Exception e) {
                log.warn("insecure-tls 初始化失败，回退系统信任库: {}", e.getMessage());
            }
        }
        return builder.build();
    }

    private record CaseRun(String verdict, long durationMs, String summary, List<Map<String, Object>> steps) {}
}
