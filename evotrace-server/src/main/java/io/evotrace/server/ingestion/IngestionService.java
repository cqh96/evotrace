package io.evotrace.server.ingestion;

import io.evotrace.common.ErrorCodes;
import io.evotrace.common.Result;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.server.project.ApiCredential;
import io.evotrace.server.project.ApiCredentialRepository;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Validates credentials & signature, checks idempotency and publishes
 * the envelope to Kafka topic {@code evo.events.raw}.
 */
@Service
public class IngestionService {

    public static final String TOPIC_RAW_EVENTS = "evo.events.raw";

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final KafkaTemplate<String, Envelope> kafkaTemplate;
    private final ApiCredentialRepository credentialRepository;
    private final ProjectRepository projectRepository;
    private final SignatureVerifier signatureVerifier;

    public IngestionService(KafkaTemplate<String, Envelope> kafkaTemplate,
                            ApiCredentialRepository credentialRepository,
                            ProjectRepository projectRepository,
                            SignatureVerifier signatureVerifier) {
        this.kafkaTemplate = kafkaTemplate;
        this.credentialRepository = credentialRepository;
        this.projectRepository = projectRepository;
        this.signatureVerifier = signatureVerifier;
    }

    /**
     * Accept an ingestion event after validating:
     * 1. API key exists and is active
     * 2. HMAC-SHA256 signature over the raw request body matches (hmac_key)
     * 3. Event is not a duplicate (idempotency key — DB unique constraint)
     */
    public Result<Map<String, Object>> accept(String apiKey, String signature, String rawBody, Envelope envelope) {
        Result<ApiCredential> validation = validate(apiKey, signature, rawBody);
        if (!validation.success()) {
            return Result.fail(validation.code(), validation.message());
        }

        String partitionKey = envelope.projectKey();
        kafkaTemplate.send(TOPIC_RAW_EVENTS, partitionKey, envelope);
        log.info("event accepted: type={} project={} idempotencyKey={}",
                envelope.eventType(), envelope.projectKey(), envelope.idempotencyKey());
        return Result.ok(Map.of("eventId", envelope.eventId(), "duplicated", false));
    }

    /**
     * Accept an event from the webhook channel. Webhook adapters construct the
     * envelope server-side, so there is no EvoTrace HMAC signature to verify
     * (GitHub's X-Hub-Signature is a separate scheme, not yet verified).
     * <p>
     * Credential resolution: header {@code X-EvoTrace-Api-Key} first, otherwise
     * the project's first ACTIVE credential.
     */
    public Result<Map<String, Object>> acceptWebhook(Envelope envelope, String headerApiKey) {
        ApiCredential credential = null;
        if (headerApiKey != null && !headerApiKey.isBlank()) {
            credential = credentialRepository.findByApiKey(headerApiKey).orElse(null);
        } else if (envelope.projectKey() != null) {
            Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                    .map(Project::getId).orElse(null);
            if (projectId != null) {
                credential = credentialRepository.findByProjectIdAndStatus(projectId, "ACTIVE")
                        .stream().findFirst().orElse(null);
            }
        }
        if (credential == null || !"ACTIVE".equals(credential.getStatus())) {
            log.warn("webhook rejected: project={} — no valid credential", envelope.projectKey());
            return Result.fail(ErrorCodes.INVALID_SIGNATURE,
                    "webhook 未配置有效凭证（缺少 X-EvoTrace-Api-Key 或项目无 ACTIVE 凭证）");
        }

        kafkaTemplate.send(TOPIC_RAW_EVENTS, envelope.projectKey(), envelope);
        log.info("webhook event accepted: type={} project={} idempotencyKey={}",
                envelope.eventType(), envelope.projectKey(), envelope.idempotencyKey());
        return Result.ok(Map.of("eventId", envelope.eventId(), "duplicated", false));
    }

    /**
     * Validate API key + HMAC signature over the raw body.
     * Shared by the event and blob endpoints.
     */
    public Result<ApiCredential> validate(String apiKey, String signature, String rawBody) {
        ApiCredential credential = credentialRepository.findByApiKey(apiKey)
                .orElse(null);
        if (credential == null || !"ACTIVE".equals(credential.getStatus())) {
            log.warn("invalid or inactive api key: {}", apiKey);
            return Result.fail(ErrorCodes.INVALID_SIGNATURE, "API Key 无效或已吊销");
        }
        String hmacKey = credential.getHmacKey();
        if (hmacKey == null || hmacKey.isBlank()) {
            return Result.fail(ErrorCodes.INVALID_SIGNATURE, "凭证未配置 hmac_key，请先轮换凭证");
        }
        if (rawBody == null || rawBody.isBlank()) {
            return Result.fail(ErrorCodes.INVALID_SIGNATURE, "缺少请求体");
        }
        if (signature == null || signature.isBlank()) {
            return Result.fail(ErrorCodes.INVALID_SIGNATURE, "缺少请求签名");
        }
        if (!signatureVerifier.verify(rawBody, hmacKey, signature)) {
            log.warn("signature verification failed: apiKey={}", apiKey);
            return Result.fail(ErrorCodes.INVALID_SIGNATURE, "签名校验失败");
        }
        return Result.ok(credential);
    }
}
