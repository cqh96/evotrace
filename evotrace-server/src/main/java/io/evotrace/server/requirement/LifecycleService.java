package io.evotrace.server.requirement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目生命周期：版本路线图（已发布 release + 未发布 target_version 聚合）
 * 与需求状态流转审计（开区间 status_history → 各状态停留时长、流转矩阵、周期）。
 */
@Service
public class LifecycleService {

    private final JdbcTemplate jdbc;

    public LifecycleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 版本路线图 ====================

    public List<Map<String, Object>> roadmap(Long projectId) {
        List<Map<String, Object>> released = jdbc.queryForList("""
                SELECT version, released_at AS "releasedAt", app_id AS "appId"
                FROM release WHERE project_id = ? ORDER BY released_at
                """, projectId);

        List<Map<String, Object>> targets = jdbc.queryForList("""
                SELECT target_version AS "version", status, count(*) AS "count"
                FROM requirement
                WHERE project_id = ? AND target_version IS NOT NULL AND target_version <> ''
                GROUP BY target_version, status
                """, projectId);

        // 已发布版本集合（用于排除）
        java.util.Set<String> releasedSet = new java.util.HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> rel : released) {
            String version = (String) rel.get("version");
            if (version == null) {
                continue;
            }
            releasedSet.add(version);
            Map<String, Object> entry = new LinkedHashMap<>(rel);
            entry.put("type", "RELEASED");
            fillStats(entry, targets, version);
            result.add(entry);
        }
        // 未发布目标版本（按版本号聚合，排除已发布）
        Map<String, Map<String, Object>> targetMap = new LinkedHashMap<>();
        for (Map<String, Object> t : targets) {
            String version = (String) t.get("version");
            if (version == null || releasedSet.contains(version)) {
                continue;
            }
            targetMap.computeIfAbsent(version, v -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("version", v);
                e.put("type", "TARGET");
                e.put("releasedAt", null);
                e.put("statusCounts", new HashMap<String, Long>());
                return e;
            });
        }
        for (Map<String, Object> t : targets) {
            String version = (String) t.get("version");
            if (releasedSet.contains(version)) {
                continue;
            }
            Map<String, Object> entry = targetMap.get(version);
            @SuppressWarnings("unchecked")
            Map<String, Long> counts = (Map<String, Long>) entry.get("statusCounts");
            counts.put((String) t.get("status"), ((Number) t.get("count")).longValue());
        }
        for (Map<String, Object> entry : targetMap.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Long> counts = (Map<String, Long>) entry.remove("statusCounts");
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            long done = counts.getOrDefault("DONE", 0L);
            entry.put("total", total);
            entry.put("done", done);
            entry.put("doneRate", total > 0 ? roundRate(done * 100.0 / total) : 0);
            result.add(entry);
        }
        return result;
    }

    private void fillStats(Map<String, Object> entry, List<Map<String, Object>> targets, String version) {
        long total = 0, done = 0;
        for (Map<String, Object> t : targets) {
            if (version.equals(t.get("version"))) {
                total += ((Number) t.get("count")).longValue();
                if ("DONE".equals(t.get("status"))) {
                    done += ((Number) t.get("count")).longValue();
                }
            }
        }
        entry.put("total", total);
        entry.put("done", done);
        entry.put("doneRate", total > 0 ? roundRate(done * 100.0 / total) : 0);
    }

    // ==================== 状态流转审计 ====================

    /** 各状态停留时长聚合 + 近 30 天流转矩阵/趋势 + 平均周期。 */
    public Map<String, Object> statusFlow(Long projectId) {
        List<Map<String, Object>> byStatus = jdbc.queryForList("""
                SELECT h.status,
                       count(*) AS "entries",
                       count(*) FILTER (WHERE h.left_at IS NULL) AS "openCount",
                       coalesce(avg(EXTRACT(EPOCH FROM (h.left_at - h.entered_at)) / 86400.0), 0) AS "avgDays",
                       coalesce(max(EXTRACT(EPOCH FROM (h.left_at - h.entered_at)) / 86400.0), 0) AS "maxDays"
                FROM requirement_status_history h
                JOIN requirement r ON r.id = h.requirement_id
                WHERE r.project_id = ?
                GROUP BY h.status ORDER BY h.status
                """, projectId);

        List<Map<String, Object>> transitions = jdbc.queryForList("""
                SELECT h.from_status AS "from", h.status AS "to", count(*) AS "count"
                FROM requirement_status_history h
                JOIN requirement r ON r.id = h.requirement_id
                WHERE r.project_id = ? AND h.entered_at > now() - interval '30 days'
                GROUP BY h.from_status, h.status ORDER BY count DESC
                """, projectId);

        List<Map<String, Object>> trend = jdbc.queryForList("""
                SELECT to_char(h.entered_at AT TIME ZONE 'Asia/Shanghai', 'MM-DD') AS "day",
                       count(*) AS "count"
                FROM requirement_status_history h
                JOIN requirement r ON r.id = h.requirement_id
                WHERE r.project_id = ? AND h.entered_at > now() - interval '30 days'
                GROUP BY 1 ORDER BY 1
                """, projectId);

        Double avgCycleDays = averageCycleDays(projectId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byStatus", byStatus);
        result.put("transitions", transitions);
        result.put("trend", trend);
        result.put("avgCycleDays", avgCycleDays != null ? roundRate(avgCycleDays) : null);
        return result;
    }

    /** 需求状态序列（开区间，durationDays null = 当前驻留）。 */
    public List<Map<String, Object>> statusHistory(Long requirementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, from_status AS "fromStatus", actor,
                       entered_at AS "enteredAt", left_at AS "leftAt"
                FROM requirement_status_history WHERE requirement_id = ?
                ORDER BY entered_at
                """, requirementId);
        for (Map<String, Object> row : rows) {
            OffsetDateTime left = odt(row.get("leftAt"));
            OffsetDateTime entered = odt(row.get("enteredAt"));
            if (left != null && entered != null) {
                row.put("durationDays", roundRate(Duration.between(entered, left).toMillis() / 86400_000.0));
            } else {
                row.put("durationDays", null);
            }
        }
        return rows;
    }

    /** 平均需求周期：DONE 行 entered_at - 该需求首条历史 entered_at 的均值（天）。 */
    private Double averageCycleDays(Long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT h.requirement_id, h.status, h.entered_at
                FROM requirement_status_history h
                JOIN requirement r ON r.id = h.requirement_id
                WHERE r.project_id = ? ORDER BY h.requirement_id, h.entered_at
                """, projectId);
        Map<Long, OffsetDateTime> firstEntered = new LinkedHashMap<>();
        Map<Long, OffsetDateTime> doneEntered = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long reqId = ((Number) row.get("requirement_id")).longValue();
            OffsetDateTime entered = odt(row.get("entered_at"));
            firstEntered.putIfAbsent(reqId, entered);
            if ("DONE".equals(row.get("status"))) {
                doneEntered.put(reqId, entered);
            }
        }
        if (doneEntered.isEmpty()) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (Map.Entry<Long, OffsetDateTime> e : doneEntered.entrySet()) {
            OffsetDateTime first = firstEntered.get(e.getKey());
            if (first != null) {
                sum += Duration.between(first, e.getValue()).toMillis() / 86400_000.0;
                count++;
            }
        }
        return count > 0 ? sum / count : null;
    }

    private static double roundRate(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** JdbcTemplate 对 timestamptz 返回 java.sql.Timestamp，统一转为 OffsetDateTime。 */
    private static OffsetDateTime odt(Object v) {
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return v instanceof OffsetDateTime o ? o : null;
    }
}
