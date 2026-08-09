package io.evotrace.server.api;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API 调试与管理（对标 Apifox / Postman）主控制器。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/apis")
public class ApiController {

    private final JdbcTemplate jdbc;
    private final ApiEndpointService endpointService;
    private final ApiDebugService debugService;
    private final ApiRepository apiRepository;

    public ApiController(JdbcTemplate jdbc, ApiEndpointService endpointService,
                         ApiDebugService debugService, ApiRepository apiRepository) {
        this.jdbc = jdbc;
        this.endpointService = endpointService;
        this.debugService = debugService;
        this.apiRepository = apiRepository;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /* ---------------- 接口清单 ---------------- */

    @GetMapping
    public Result<List<ApiRepository.Endpoint>> list(@PathVariable String projectKey) {
        return Result.ok(endpointService.list(projectId(projectKey)));
    }

    @PostMapping("/sync")
    public Result<Map<String, Object>> sync(@PathVariable String projectKey) {
        int n = endpointService.syncFromInventory(projectId(projectKey));
        return Result.ok(Map.of("synced", n));
    }

    public record ImportBody(String format, Long appId, String content) {}

    @PostMapping("/import")
    public Result<Map<String, Object>> importApis(@PathVariable String projectKey, @RequestBody ImportBody body) {
        if (body.content() == null || body.content().isBlank()) {
            return Result.fail("EVO-BIZ-400", "导入内容为空");
        }
        try {
            int n = endpointService.importDrafts(projectId(projectKey), body.appId(), body.format(), body.content());
            return Result.ok(Map.of("imported", n));
        } catch (IllegalArgumentException e) {
            return Result.fail("EVO-BIZ-400", e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Result<ApiRepository.Endpoint> detail(@PathVariable String projectKey, @PathVariable Long id) {
        return Result.ok(endpointService.get(id));
    }

    public record DetailBody(String name, String summary, List<Map<String, Object>> params,
                             Map<String, Object> requestBody, Map<String, Object> responseSchema,
                             Map<String, Object> mockResponse) {}

    @PutMapping("/{id}")
    public Result<Void> updateDetail(@PathVariable String projectKey, @PathVariable Long id,
                                     @RequestBody DetailBody body) {
        apiRepository.updateDetail(id, body.name(), body.summary(), body.params(),
                body.requestBody(), body.responseSchema(), body.mockResponse());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable String projectKey, @PathVariable Long id) {
        apiRepository.delete(id);
        return Result.ok(null);
    }

    /* ---------------- 调试 & Mock ---------------- */

    public record DebugBody(Map<String, Object> request, String baseUrl) {}

    @PostMapping("/{id}/debug")
    public Result<ApiDebugService.DebugResult> debug(@PathVariable String projectKey, @PathVariable Long id,
                                                     @RequestBody(required = false) DebugBody body) {
        ApiRepository.Endpoint ep = endpointService.get(id);
        if (ep == null) return Result.fail("EVO-BIZ-404", "接口不存在");
        Map<String, Object> request = body == null ? Map.of() : body.request();
        String baseUrl = body == null ? null : body.baseUrl();
        try {
            return Result.ok(debugService.debug(projectId(projectKey), ep.appId(), ep.method(), ep.path(),
                    request, baseUrl));
        } catch (IllegalArgumentException e) {
            return Result.fail("EVO-BIZ-400", e.getMessage());
        }
    }

    @GetMapping("/{id}/mock")
    public Result<Map<String, Object>> mock(@PathVariable String projectKey, @PathVariable Long id) {
        try {
            return Result.ok(debugService.mock(projectId(projectKey), id));
        } catch (IllegalArgumentException e) {
            return Result.fail("EVO-BIZ-404", e.getMessage());
        }
    }

    /* ---------------- 环境管理 ---------------- */

    @GetMapping("/environments")
    public Result<List<ApiRepository.Environment>> environments(@PathVariable String projectKey) {
        return Result.ok(apiRepository.listEnvironments(projectId(projectKey)));
    }

    public record EnvBody(Long id, String name, String baseUrl, Map<String, String> headers,
                          Map<String, Object> variables) {}

    @PostMapping("/environments")
    public Result<Void> saveEnvironment(@PathVariable String projectKey, @RequestBody EnvBody body) {
        apiRepository.saveEnvironment(projectId(projectKey), body.id(), body.name(), body.baseUrl(),
                body.headers(), body.variables());
        return Result.ok(null);
    }

    @DeleteMapping("/environments/{id}")
    public Result<Void> deleteEnvironment(@PathVariable String projectKey, @PathVariable Long id) {
        apiRepository.deleteEnvironment(id);
        return Result.ok(null);
    }

    /* ---------------- 用例管理 ---------------- */

    @GetMapping("/test-cases")
    public Result<List<ApiRepository.TestCase>> testCases(@PathVariable String projectKey) {
        return Result.ok(apiRepository.listTestCases(projectId(projectKey)));
    }

    public record TestCaseBody(Long id, Long endpointId, String name, Map<String, Object> request,
                               Map<String, Object> response, Integer expectedStatus, Integer lastStatus,
                               Integer lastDurationMs) {}

    @PostMapping("/test-cases")
    public Result<Void> saveTestCase(@PathVariable String projectKey, @RequestBody TestCaseBody body) {
        apiRepository.saveTestCase(projectId(projectKey), body.id(), body.endpointId(), body.name(),
                body.request(), body.response(), body.expectedStatus(), body.lastStatus(), body.lastDurationMs());
        return Result.ok(null);
    }

    @DeleteMapping("/test-cases/{id}")
    public Result<Void> deleteTestCase(@PathVariable String projectKey, @PathVariable Long id) {
        apiRepository.deleteTestCase(id);
        return Result.ok(null);
    }
}