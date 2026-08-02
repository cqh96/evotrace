package io.evotrace.server.testplan;

import io.evotrace.common.Result;
import io.evotrace.server.ingestion.IngestionService;
import io.evotrace.server.ingestion.RawBodyCaptureFilter;
import io.evotrace.server.project.ApiCredential;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Open test-result ingestion for external runners (CI scripts, Playwright
 * runners executing UI-test steps). Authenticated the same way as event
 * ingestion (API key + HMAC signature over the raw body); the credential's
 * project is the target, so a runner can never write into another project.
 */
@RestController
@RequestMapping("/open-api/v1")
public class TestResultController {

    private final IngestionService ingestionService;
    private final JdbcTemplate jdbc;

    public TestResultController(IngestionService ingestionService, JdbcTemplate jdbc) {
        this.ingestionService = ingestionService;
        this.jdbc = jdbc;
    }

    @PostMapping("/test-results")
    public Result<Map<String, Object>> report(@RequestHeader("X-EvoTrace-Api-Key") String apiKey,
                                              @RequestHeader("X-EvoTrace-Signature") String signature,
                                              @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        String rawBody = (String) request.getAttribute(RawBodyCaptureFilter.RAW_BODY_ATTR);
        Result<ApiCredential> validation = ingestionService.validate(apiKey, signature, rawBody);
        if (!validation.success()) {
            return Result.fail(validation.code(), validation.message());
        }

        // The credential's project is the trust boundary
        Long projectId = validation.data().getProjectId();
        if (body.get("projectKey") != null) {
            String expected = jdbc.queryForObject(
                    "SELECT project_key FROM project WHERE id = ?", String.class, projectId);
            if (!expected.equals(body.get("projectKey"))) {
                return Result.fail("EVO-AUTH-001", "projectKey 与凭证项目不一致");
            }
        }

        String status = String.valueOf(body.getOrDefault("status", "PASSED"));
        Object caseId = body.get("testCaseId");
        Object planItemId = body.get("planItemId");

        if (planItemId != null) {
            // Update plan item (runner executed a planned case)
            int updated = jdbc.update("""
                    UPDATE test_plan_item SET status = ?, executor = ?, result_detail = ?, executed_at = now()
                    WHERE id = ? AND plan_id IN (SELECT id FROM test_plan WHERE project_id = ?)
                    """, status, body.get("executor"), body.get("resultDetail"), planItemId, projectId);
            if (updated == 0) {
                return Result.fail("EVO-BIZ-001", "计划项不存在或不属于该项目");
            }
            return Result.ok(Map.of("planItemId", planItemId, "status", status));
        }

        if (caseId == null) {
            return Result.fail("EVO-BIZ-001", "缺少 testCaseId 或 planItemId");
        }
        Long testCaseId = ((Number) caseId).longValue();
        int exists = jdbc.queryForObject(
                "SELECT count(*) FROM test_case WHERE id = ? AND project_id = ?",
                Integer.class, testCaseId, projectId);
        if (exists == 0) {
            return Result.fail("EVO-BIZ-001", "测试用例不存在或不属于该项目");
        }

        Long id = jdbc.queryForObject("""
                INSERT INTO test_execution(test_case_id, release_id, executor, status, result_detail, executed_at)
                VALUES (?, ?, ?, ?, ?, COALESCE(?::timestamptz, now())) RETURNING id
                """, Long.class, testCaseId, body.get("releaseId"), body.get("executor"),
                status, body.get("resultDetail"), body.get("executedAt"));
        return Result.ok(Map.of("id", id, "status", status));
    }
}
