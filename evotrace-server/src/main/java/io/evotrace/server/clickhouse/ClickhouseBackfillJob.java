package io.evotrace.server.clickhouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 历史回填 Job（V2.5）。
 * <p>
 * 从 PostgreSQL 的 change_event 分批导历史事件入 ClickHouse，供双写灰度稳定后的历史归档。
 * 幂等：按 (sha256(event_id)) 或 event_id 去重，重复执行安全。
 */
@Component
@ConditionalOnProperty(prefix = "evotrace.clickhouse", name = "enabled", havingValue = "true")
public class ClickhouseBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ClickhouseBackfillJob.class);

    private final JdbcTemplate pg;
    private final ObjectProvider<ClickhouseRepository> clickhouseRepositoryProvider;

    @Value("${evotrace.clickhouse.backfill.enabled:false}")
    private boolean enabled;

    @Value("${evotrace.clickhouse.backfill.batch-size:5000}")
    private int batchSize;

    public ClickhouseBackfillJob(JdbcTemplate pg,
                                 ObjectProvider<ClickhouseRepository> clickhouseRepositoryProvider) {
        this.pg = pg;
        this.clickhouseRepositoryProvider = clickhouseRepositoryProvider;
    }

    @Scheduled(cron = "${evotrace.clickhouse.backfill.cron:0 30 4 * * *}")
    public void backfill() {
        if (!enabled) {
            return;
        }
        ClickhouseRepository repository = clickhouseRepositoryProvider.getIfAvailable();
        if (repository == null) {
            log.info("ClickHouse backfill skipped: repository unavailable");
            return;
        }
        long lastId = findLastSyncedId();
        long upper = findMaxId();
        long cursor = lastId;
        long total = 0;
        while (cursor <= upper) {
            List<Map<String, Object>> rows = pg.queryForList("""
                            SELECT id, project_id, app_id, event_id, event_type,
                                   branch, commit_sha, author, blob_ref, occurred_at
                            FROM change_event
                            WHERE id > ? AND id <= ?
                            ORDER BY id
                            LIMIT ?
                            """, cursor, upper, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> r : rows) {
                repository.upsertEvent(
                        ((Number) r.get("id")).longValue(),
                        ((Number) r.get("project_id")).longValue(),
                        r.get("app_id") == null ? null : ((Number) r.get("app_id")).longValue(),
                        (String) r.get("event_id"),
                        (String) r.get("event_type"),
                        (String) r.get("branch"),
                        (String) r.get("commit_sha"),
                        (String) r.get("author"),
                        (String) r.get("commit_message") == null ? "" : (String) r.get("commit_message"),
                        (String) r.get("blob_ref"),
                        (OffsetDateTime) r.get("occurred_at"));
                cursor = ((Number) r.get("id")).longValue();
                total++;
            }
        }
        log.info("ClickHouse backfill complete: {} events (cursor={})", total, cursor);
        persistSyncCursor(cursor);
    }

    private long findLastSyncedId() {
        Long v = pg.queryForObject("SELECT coalesce(meta_value, '0')::bigint FROM meta WHERE meta_key = 'clickhouse.backfill.last_id'",
                Long.class);
        return v == null ? 0L : v;
    }

    private long findMaxId() {
        Long max = pg.queryForObject("SELECT coalesce(max(id), 0) FROM change_event", Long.class);
        return max == null ? 0L : max;
    }

    private void persistSyncCursor(long cursor) {
        pg.update("""
                INSERT INTO meta (meta_key, meta_value) VALUES ('clickhouse.backfill.last_id', ?)
                ON CONFLICT (meta_key) DO UPDATE SET meta_value = EXCLUDED.meta_value, updated_at = now()
                """, String.valueOf(cursor));
    }
}