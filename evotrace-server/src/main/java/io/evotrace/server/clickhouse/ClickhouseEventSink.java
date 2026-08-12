package io.evotrace.server.clickhouse;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.server.project.Project;
import io.evotrace.server.project.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ClickHouse 事件双写网关（V2.5）。
 * <p>
 * 监听 {@code evo.events.raw} 原始事件，在 {@code evotrace.clickhouse.dual-write=true}
 * 且 ClickHouse 已启用时，将事件追加写入 ClickHouse，实现 PostgreSQL + ClickHouse 双写灰度过渡。
 * 未启用时安全空转，不影响主链路。
 */
@Component
public class ClickhouseEventSink {

    private static final Logger log = LoggerFactory.getLogger(ClickhouseEventSink.class);

    private final ObjectProvider<ClickhouseRepository> clickhouseRepositoryProvider;
    private final ProjectRepository projectRepository;

    @Value("${evotrace.clickhouse.dual-write:false}")
    private boolean dualWrite;

    public ClickhouseEventSink(ObjectProvider<ClickhouseRepository> clickhouseRepositoryProvider,
                               ProjectRepository projectRepository) {
        this.clickhouseRepositoryProvider = clickhouseRepositoryProvider;
        this.projectRepository = projectRepository;
    }

    @KafkaListener(topics = "evo.events.raw", groupId = "evo-clickhouse-sink")
    public void onEvent(Envelope envelope) {
        if (!dualWrite || envelope == null) {
            return;
        }
        ClickhouseRepository repository = clickhouseRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return; // ClickHouse 未启用，降级
        }
        try {
            Long projectId = projectRepository.findByProjectKey(envelope.projectKey())
                    .map(Project::getId).orElse(null);
            if (projectId == null) {
                return;
            }
            String branch = payloadStr(envelope, "branch");
            String commitSha = payloadStr(envelope, "commitSha");
            String message = payloadStr(envelope, "message");
            String author = payloadStr(envelope, "authorName");
            repository.upsertEvent(
                    envelope.eventId().hashCode() & 0x7fffffff, // 稳定数值 id（由 eventId 派生）
                    projectId,
                    null,
                    envelope.eventId(),
                    envelope.eventType() != null ? envelope.eventType().name() : "UNKNOWN",
                    branch, commitSha, author != null ? author : "system", message,
                    envelope.blobRef(),
                    envelope.occurredAt());
            log.debug("clickhouse dual-write event: {}", envelope.eventId());
        } catch (Exception e) {
            log.warn("clickhouse dual-write skipped (eventId={}): {}", envelope.eventId(), e.getMessage());
        }
    }

    private static String payloadStr(Envelope envelope, String key) {
        return envelope.payload() != null ? (String) envelope.payload().get(key) : null;
    }
}