package io.evotrace.server.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.evotrace.common.Result;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventSource;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.ai.ModelRouter;
import io.evotrace.server.change.ChangeEvent;
import io.evotrace.server.change.ChangeEventRepository;
import io.evotrace.server.ingestion.SignatureVerifier;
import io.evotrace.server.project.ApiCredential;
import io.evotrace.server.project.ApiCredentialRepository;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 环境自检（全链路演练）：验证 服务器/DB/Kafka 连通性、OpenAPI 凭证有效性，
 * 并发送一条真实 CODE_COMMIT 样例事件走完整 ingestion 链路
 * （HMAC 签名 → RawBodyCaptureFilter → SignatureVerifier → Kafka → CommitHandler → 入库 → AI 摘要任务），
 * 确认事件落在项目时间线上。
 * <p>
 * 注意：样例事件会真实出现在项目演化时间线（消息为「环境自检样例事件…」），
 * 这是有意的平台演示事件；payload 无文件变更、无迭代键，不产生 change_file/迭代/发布副作用。
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);
    private static final String PROBE_TOPIC = "evo.diagnostics.probe";
    private static final long PERSIST_POLL_MS = 250;
    private static final int PERSIST_POLL_MAX = 20; // 最多等 5s
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    // 代码库惯例：Jackson 2 ObjectMapper 无 Spring bean（Spring Boot 4 自带的是 Jackson 3），
    // 与 SDK InventoryReporter 一致的静态实例（jsr310 + ISO-8601 时间串，保证签名与反序列化兼容）。
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final JdbcTemplate jdbc;
    private final ProjectRepository projectRepository;
    private final ApiCredentialRepository credentialRepository;
    private final ChangeEventRepository changeEventRepository;
    private final SignatureVerifier signatureVerifier;
    private final KafkaTemplate<String, Envelope> kafkaTemplate;
    private final ModelRouter modelRouter;
    private final String serverUrl;

    public DiagnosticsService(JdbcTemplate jdbc, ProjectRepository projectRepository,
                              ApiCredentialRepository credentialRepository,
                              ChangeEventRepository changeEventRepository,
                              SignatureVerifier signatureVerifier,
                              KafkaTemplate<String, Envelope> kafkaTemplate,
                              ModelRouter modelRouter,
                              @Value("${evotrace.server-url:}") String serverUrl) {
        this.jdbc = jdbc;
        this.projectRepository = projectRepository;
        this.credentialRepository = credentialRepository;
        this.changeEventRepository = changeEventRepository;
        this.signatureVerifier = signatureVerifier;
        this.kafkaTemplate = kafkaTemplate;
        this.modelRouter = modelRouter;
        this.serverUrl = serverUrl;
    }

    // ==================== 服务端状态 ====================

    public Map<String, Object> serverCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", OffsetDateTime.now().toString());

        long start = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            result.put("db", Map.of("ok", true, "latencyMs",
                    (System.nanoTime() - start) / 1_000_000));
        } catch (Exception e) {
            log.warn("diagnostics: db check failed", e);
            result.put("db", Map.of("ok", false, "latencyMs", 0,
                    "message", e.getMessage()));
        }

        result.put("kafka", probeKafka());
        result.put("ok", Boolean.TRUE.equals(getOk(result, "db")) && Boolean.TRUE.equals(getOk(result, "kafka")));
        return result;
    }

    private Map<String, Object> probeKafka() {
        long start = System.nanoTime();
        try {
            // 复用应用自身 producer（JsonSerializer）向探活 topic 发送最小信封并等待 ack；
            // 该 topic 无消费者、无副作用，broker 会自动创建。
            Envelope probe = new Envelope(Envelope.CURRENT_VERSION, "diag-probe", "__diag__", null,
                    EventType.CODE_COMMIT, OffsetDateTime.now(), EventSource.OPEN_API,
                    "diag:probe:" + UUID.randomUUID(), Map.of("probe", true), null);
            kafkaTemplate.send(PROBE_TOPIC, "diag", probe).get(3, TimeUnit.SECONDS);
            return Map.of("ok", true, "message", "probe acked in "
                    + (System.nanoTime() - start) / 1_000_000 + "ms");
        } catch (Exception e) {
            log.warn("diagnostics: kafka probe failed", e);
            return Map.of("ok", false, "message", "probe 失败: " + rootMessage(e));
        }
    }

    // ==================== 凭证校验 ====================

    public Result<Map<String, Object>> credentialCheck(Long projectId) {
        Optional<ApiCredential> opt = credentialRepository.findByProjectIdAndStatus(projectId, "ACTIVE")
                .stream().findFirst();
        if (opt.isEmpty()) {
            return Result.fail("EVO-BIZ-001", "项目未配置 ACTIVE 凭证（请在项目设置中轮换生成）");
        }
        ApiCredential credential = opt.get();
        String hmacKey = credential.getHmacKey();
        boolean hmacConfigured = hmacKey != null && !hmacKey.isBlank();
        boolean signatureSelfTest = false;
        if (hmacConfigured) {
            // 本地签/验回环，证明 HMAC 能力可用（不依赖外部请求）
            String sig = signatureVerifier.sign("evotrace-selfcheck-probe", hmacKey);
            signatureSelfTest = signatureVerifier.verify("evotrace-selfcheck-probe", hmacKey, sig);
        }
        String apiKey = credential.getApiKey();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("apiKeyPrefix", apiKey != null && apiKey.length() > 10
                ? apiKey.substring(0, 10) + "…" : apiKey);
        result.put("status", credential.getStatus());
        result.put("hmacConfigured", hmacConfigured);
        result.put("signatureSelfTest", signatureSelfTest);
        result.put("expiresAt", credential.getExpiresAt());
        result.put("ok", "ACTIVE".equals(credential.getStatus()) && hmacConfigured && signatureSelfTest);
        return Result.ok(result);
    }

    // ==================== 全链路样例事件 ====================

    public Result<Map<String, Object>> sendSample(String projectKey, HttpServletRequest request) {
        Optional<Project> projectOpt = projectRepository.findByProjectKey(projectKey);
        if (projectOpt.isEmpty()) {
            return Result.fail("EVO-BIZ-404", "项目不存在: " + projectKey);
        }
        Project project = projectOpt.get();
        Optional<ApiCredential> credOpt = credentialRepository.findByProjectIdAndStatus(project.getId(), "ACTIVE")
                .stream().findFirst();
        if (credOpt.isEmpty()) {
            return Result.fail("EVO-BIZ-001", "项目未配置 ACTIVE 凭证（请在项目设置中轮换生成）");
        }
        ApiCredential credential = credOpt.get();
        if (credential.getHmacKey() == null || credential.getHmacKey().isBlank()) {
            return Result.fail("EVO-BIZ-001", "凭证未配置 hmac_key，请先轮换生成新凭证");
        }

        String eventId = "diag-" + UUID.randomUUID();
        String idempotencyKey = "diag:" + projectKey + ":" + UUID.randomUUID();
        String message = "环境自检样例事件 " + projectKey + " " + OffsetDateTime.now();
        CodeCommitPayload payload = new CodeCommitPayload(project.getRepoUrl(), "diag/selftest",
                "diag0001", List.of(), "selfcheck", "selfcheck@evotrace.local", message, List.of());
        Envelope envelope = new Envelope(Envelope.CURRENT_VERSION, eventId, projectKey, null,
                EventType.CODE_COMMIT, OffsetDateTime.now(), EventSource.OPEN_API,
                idempotencyKey, mapper.convertValue(payload, MAP_TYPE), null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", eventId);
        result.put("projectKey", projectKey);
        result.put("accepted", false);
        result.put("duplicated", false);
        result.put("persisted", false);
        result.put("timelineVisible", false);
        result.put("aiModelUsable", modelRouter.hasUsableModel());

        try {
            String rawBody = mapper.writeValueAsString(envelope);
            String signature = signatureVerifier.sign(rawBody, credential.getHmacKey());
            String url = resolveServerUrl(request) + "/open-api/v1/events";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-EvoTrace-Api-Key", credential.getApiKey())
                    .header("X-EvoTrace-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(rawBody))
                    .build();
            HttpResponse<String> resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());
            result.put("httpStatus", resp.statusCode());

            Map<String, Object> respBody = mapper.readValue(resp.body(), MAP_TYPE);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && Boolean.TRUE.equals(respBody.get("success"))) {
                result.put("accepted", true);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) respBody.getOrDefault("data", Map.of());
                result.put("duplicated", Boolean.TRUE.equals(data.get("duplicated")));
            } else {
                result.put("error", "ingestion 拒绝: " + respBody.get("message"));
            }
        } catch (Exception e) {
            log.warn("diagnostics: sample send failed", e);
            result.put("error", "发送失败: " + rootMessage(e));
        }

        // 轮询等待落库（Kafka 消费为异步）
        long start = System.nanoTime();
        Optional<ChangeEvent> event = Optional.empty();
        for (int i = 0; i < PERSIST_POLL_MAX && event.isEmpty(); i++) {
            event = changeEventRepository.findByEventId(eventId);
            if (event.isEmpty()) {
                try {
                    Thread.sleep(PERSIST_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (event.isPresent()) {
            result.put("persisted", true);
            result.put("persistAfterMs", (System.nanoTime() - start) / 1_000_000);
            result.put("summaryStatus", event.get().getSummaryStatus());
            result.put("timelineVisible", isTimelineVisible(projectKey, eventId));
        } else {
            result.put("error", result.get("error") != null ? result.get("error")
                    : "事件已受理但未在 5s 内落库（请检查 Kafka 消费者/CommitHandler 日志）");
        }
        result.put("ok", Boolean.TRUE.equals(result.get("accepted"))
                && Boolean.TRUE.equals(result.get("persisted")));
        return Result.ok(result);
    }

    /** 查询样例事件的落地状态（AI 摘要异步生成，前端轮询）。 */
    public Result<Map<String, Object>> sampleStatus(String projectKey, String eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.summary_status AS "summaryStatus", c.occurred_at AS "occurredAt",
                       s.content AS "summaryPreview", s.model AS "summaryModel"
                FROM change_event c
                JOIN project p ON p.id = c.project_id
                LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT'
                     AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE p.project_key = ? AND c.event_id = ?
                """, projectKey, eventId);
        if (rows.isEmpty()) {
            return Result.fail("EVO-BIZ-404", "样例事件未找到");
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("eventId", eventId);
        result.put("timelineVisible", isTimelineVisible(projectKey, eventId));
        result.put("aiModelUsable", modelRouter.hasUsableModel());
        return Result.ok(result);
    }

    // ==================== 工具 ====================

    /** 与时间线查询同源（project_key + event_id 过滤），证明样例事件在时间线可见。 */
    private boolean isTimelineVisible(String projectKey, String eventId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM change_event c
                JOIN project p ON p.id = c.project_id
                LEFT JOIN application a ON a.id = c.app_id
                WHERE p.project_key = ? AND c.event_id = ?
                """, Integer.class, projectKey, eventId);
        return count != null && count > 0;
    }

    /** 自检回环地址：优先配置 evotrace.server-url，否则从请求头/主机名推导（尊重代理转发头）。 */
    private String resolveServerUrl(HttpServletRequest request) {
        if (serverUrl != null && !serverUrl.isBlank()) {
            return serverUrl.replaceAll("/+$", "");
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");
        if (host != null && !host.isBlank()) {
            return (proto != null && !proto.isBlank() ? proto : request.getScheme()) + "://" + host;
        }
        int port = request.getServerPort();
        String scheme = request.getScheme();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static boolean getOk(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok"));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg != null ? msg : t.getClass().getSimpleName();
    }
}
