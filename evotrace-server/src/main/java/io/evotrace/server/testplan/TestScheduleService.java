package io.evotrace.server.testplan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 定时调度（对标 MeterSphere + CI 持续测试）：按 cron 触发测试计划执行。
 * <p>单机轮询实现：每 30s 扫描应执行的计划并提交到 {@link TestExecutionRunner}。
 * 不引入消息队列，避免过度建设。</p>
 */
@Service
public class TestScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TestScheduleService.class);

    private final JdbcTemplate jdbc;
    private final TestExecutionRunner runner;

    public TestScheduleService(JdbcTemplate jdbc, TestExecutionRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT s.id, s.project_id AS "projectId", s.plan_id AS "planId",
                       tp.name AS "planName", s.name, s.cron, s.enabled,
                       s.last_run_at AS "lastRunAt", s.created_by AS "createdBy"
                FROM test_schedule s JOIN test_plan tp ON tp.id = s.plan_id
                WHERE s.project_id = ?
                ORDER BY s.id
                """, projectId);
    }

    @Transactional
    public Long create(Long projectId, Long planId, String name, String cron) {
        return jdbc.queryForObject("""
                INSERT INTO test_schedule(project_id, plan_id, name, cron) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, projectId, planId, name, cron);
    }

    @Transactional
    public void update(Long projectId, Long scheduleId, String name, String cron, Boolean enabled) {
        jdbc.update("""
                UPDATE test_schedule SET name = COALESCE(?, name), cron = COALESCE(?, cron),
                    enabled = COALESCE(?, enabled) WHERE id = ? AND project_id = ?
                """, name, cron, enabled, scheduleId, projectId);
    }

    @Transactional
    public void delete(Long projectId, Long scheduleId) {
        jdbc.update("DELETE FROM test_schedule WHERE id = ? AND project_id = ?", scheduleId, projectId);
    }

    /** 每 30s 扫描：enabled 且 cron 匹配当前分钟则执行对应计划。 */
    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        String match = "%02d%02d".formatted(now.getHour(), now.getMinute());
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT s.id AS "scheduleId", s.project_id AS "projectId", s.plan_id AS "planId",
                       s.last_run_at AS "lastRunAt", s.cron
                FROM test_schedule s
                WHERE s.enabled = TRUE
                """);
        for (Map<String, Object> s : due) {
            if (!cronMatches(String.valueOf(s.get("cron")), match)) continue;
            java.sql.Timestamp lastRun = (java.sql.Timestamp) s.get("lastRunAt");
            if (lastRun != null && lastRun.toLocalDateTime().getMinute() == now.getMinute()) {
                continue; // 本分钟已执行
            }
            Long projectId = ((Number) s.get("projectId")).longValue();
            Long planId = ((Number) s.get("planId")).longValue();
            try {
                runner.runPlan(projectId, planId, Map.of());
                jdbc.update("UPDATE test_schedule SET last_run_at = now() WHERE id = ?", s.get("scheduleId"));
                log.info("scheduled plan executed: schedule={} plan={}", s.get("scheduleId"), planId);
            } catch (Exception e) {
                log.warn("scheduled plan failed: schedule={} error={}", s.get("scheduleId"), e.getMessage());
            }
        }
    }

    /** 简化 cron：支持 "分 时 * * *"，提取 HHmm 匹配。 */
    private boolean cronMatches(String cron, String match) {
        if (cron == null || cron.isBlank()) return false;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 2) return false;
        String minute = parts[0];
        String hour = parts[1];
        int hh = Integer.parseInt(match.substring(0, 2));
        int mm = Integer.parseInt(match.substring(2));
        return wildcard(hour, hh) && wildcard(minute, mm);
    }

    private boolean wildcard(String field, int value) {
        if ("*".equals(field) || "*/1".equals(field)) return true;
        if (field.contains("/")) {
            String[] p = field.split("/");
            int step = Integer.parseInt(p[1]);
            return value % step == 0;
        }
        try {
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}