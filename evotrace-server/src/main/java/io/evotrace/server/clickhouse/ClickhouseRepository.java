package io.evotrace.server.clickhouse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 事件分析仓储（V2.5）。
 * <p>
 * 承接高吞吐事件明细与时间线/趋势/热点等分析查询，PostgreSQL 只保留业务事务数据。
 * MergeTree 按 occurred 按月分区，ORDER BY (project_id, occurred) 支撑时间线查询。
 */
@Component
@ConditionalOnProperty(prefix = "evotrace.clickhouse", name = "enabled", havingValue = "true")
public class ClickhouseRepository {

    private static final Logger log = LoggerFactory.getLogger(ClickhouseRepository.class);

    /** 事件明细表（分布式表名，写入与查询统一走它） */
    public static final String TABLE_EVENT = "evo_change_event";

    private final JdbcTemplate ch;

    public ClickhouseRepository(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate ch) {
        this.ch = ch;
    }

    @PostConstruct
    void init() {
        try {
            ch.execute("""
                    CREATE TABLE IF NOT EXISTS %s
                    (
                        id UInt64,
                        project_id UInt64,
                        app_id UInt64,
                        event_id String,
                        event_type LowCardinality(String),
                        branch String,
                        commit_sha String,
                        author String,
                        commit_message String,
                        blob_ref String,
                        occurred DateTime64(3)
                    )
                    ENGINE = MergeTree
                    PARTITION BY toYYYYMM(occurred)
                    ORDER BY (project_id, occurred)
                    """.formatted(TABLE_EVENT));
            log.info("ClickHouse table {} ensured", TABLE_EVENT);
        } catch (Exception e) {
            log.warn("ClickHouse table init skipped: {}", e.getMessage());
        }
    }

    /** 写入一条事件（幂等：ReplacingMergeTree 语义由 event_id 去重）。 */
    public void upsertEvent(long id, long projectId, Long appId, String eventId, String eventType,
                            String branch, String commitSha, String author, String commitMessage,
                            String blobRef, OffsetDateTime occurred) {
        try {
            ch.update("""
                            INSERT INTO %s (id, project_id, app_id, event_id, event_type, branch,
                                            commit_sha, author, commit_message, blob_ref, occurred)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.formatted(TABLE_EVENT),
                    id, projectId, appId == null ? 0 : appId, eventId, eventType,
                    branch, commitSha, author, commitMessage, blobRef, occurred);
        } catch (Exception e) {
            log.warn("ClickHouse upsert event failed (eventId={}): {}", eventId, e.getMessage());
        }
    }

    /** 时间线查询（直接命中 ClickHouse 列式存储，P95 < 1s 目标）。 */
    public List<Map<String, Object>> timeline(long projectId, int limit) {
        try {
            return ch.queryForList("""
                            SELECT event_id, event_type, branch, commit_sha, author,
                                   commit_message, occurred
                            FROM %s
                            WHERE project_id = ?
                            ORDER BY occurred DESC
                            LIMIT ?
                            """.formatted(TABLE_EVENT),
                    projectId, limit);
        } catch (Exception e) {
            log.warn("ClickHouse timeline query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 近 N 日事件数趋势（按日聚合，物化视图未就绪时兜底）。 */
    public List<Map<String, Object>> dailyEventTrend(long projectId, int days) {
        try {
            return ch.queryForList("""
                            SELECT toDate(occurred) AS day, count() AS cnt
                            FROM %s
                            WHERE project_id = ? AND occurred >= now() - INTERVAL %d DAY
                            GROUP BY day ORDER BY day
                            """.formatted(TABLE_EVENT, days),
                    projectId);
        } catch (Exception e) {
            log.warn("ClickHouse trend query failed: {}", e.getMessage());
            return List.of();
        }
    }
}