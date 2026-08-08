package io.evotrace.server.diagnostics;

import io.evotrace.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 环境自检（全链路演练）接口：服务器/DB/Kafka 状态、OpenAPI 凭证校验、
 * 发送真实样例事件走完整 ingestion 链路并验证时间线可见性。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/diagnostics")
public class DiagnosticsController {

    private final JdbcTemplate jdbc;
    private final DiagnosticsService diagnosticsService;

    public DiagnosticsController(JdbcTemplate jdbc, DiagnosticsService diagnosticsService) {
        this.jdbc = jdbc;
        this.diagnosticsService = diagnosticsService;
    }

    private Long projectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    /** 服务端状态：DB / Kafka 连通性。 */
    @GetMapping("/server")
    public Result<Map<String, Object>> serverCheck(@PathVariable String projectKey) {
        return Result.ok(diagnosticsService.serverCheck());
    }

    /** OpenAPI 凭证校验（存在性 / 状态 / hmac 配置 / 签名自校验）。 */
    @GetMapping("/credential")
    public Result<Map<String, Object>> credentialCheck(@PathVariable String projectKey) {
        return diagnosticsService.credentialCheck(projectId(projectKey));
    }

    /** 全链路自检：发送样例 CODE_COMMIT 事件，验证签名→Kafka→入库→时间线可见。 */
    @PostMapping("/send-sample")
    public Result<Map<String, Object>> sendSample(@PathVariable String projectKey,
                                                  HttpServletRequest request) {
        return diagnosticsService.sendSample(projectKey, request);
    }

    /** 样例事件落地状态轮询（AI 摘要异步生成）。 */
    @GetMapping("/sample")
    public Result<Map<String, Object>> sampleStatus(@PathVariable String projectKey,
                                                    @RequestParam String eventId) {
        return diagnosticsService.sampleStatus(projectKey, eventId);
    }
}
