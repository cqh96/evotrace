package io.evotrace.server.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按版本时间窗写 SHIPPED_IN 边并刷新 e2e_trace（docs/10 §8.4.5 ChangeSet 自动纳入规则）。
 */
@Service
public class TraceRebuildService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ArtifactLinkService linkService;

    public TraceRebuildService(JdbcTemplate jdbc, ArtifactLinkService linkService) {
        this.jdbc = jdbc;
        this.linkService = linkService;
    }

    /**
     * 重算某 release 的 SHIPPED_IN 边。
     * 时间窗（同一 app_id）：prev.released_at &lt; change.occurred_at ≤ curr.released_at；
     * 无上一版本时取 release 前默认 14 天窗口。
     * 返回写入的 change 数量。
     */
    public int rebuildChangeset(Long projectId, Long releaseId) {
        Map<String, Object> rel = jdbc.queryForMap("SELECT * FROM release WHERE id = ? AND project_id = ?",
                releaseId, projectId);
        OffsetDateTime curr = (OffsetDateTime) rel.get("released_at");
        Long appId = rel.get("app_id") instanceof Number n ? n.longValue() : null;

        for (Map<String, Object> change : changesInWindow(projectId, releaseId, appId, curr)) {
            String eventId = String.valueOf(change.get("event_id"));
            linkService.upsertAuto(projectId, "CHANGE_EVENT", eventId, "RELEASE",
                    String.valueOf(releaseId), "SHIPPED_IN", 100, "AUTO_TIME_WINDOW",
                    Map.of("releaseId", releaseId));
            // 该变更 IMPLEMENTS 的需求也写 SHIPPED_IN
            List<Map<String, Object>> reqs = jdbc.queryForList("""
                    SELECT to_id FROM artifact_link
                    WHERE project_id = ? AND from_type = 'CHANGE_EVENT' AND from_id = ?
                      AND link_type = 'IMPLEMENTS' AND status = 'ACTIVE'
                    """, projectId, eventId);
            for (Map<String, Object> r : reqs) {
                linkService.upsertAuto(projectId, "REQUIREMENT", String.valueOf(r.get("to_id")), "RELEASE",
                        String.valueOf(releaseId), "SHIPPED_IN", 100, "AUTO_TIME_WINDOW",
                        Map.of("releaseId", releaseId));
            }
        }
        return countChangesInWindow(projectId, releaseId);
    }

    /** 返回 release 时间窗内的 CODE_COMMIT/MR_MERGED 变更（供版本全景统计复用）。 */
    public List<Map<String, Object>> changesInWindow(Long projectId, Long releaseId) {
        Map<String, Object> rel = jdbc.queryForMap("SELECT * FROM release WHERE id = ? AND project_id = ?",
                releaseId, projectId);
        OffsetDateTime curr = (OffsetDateTime) rel.get("released_at");
        Long appId = rel.get("app_id") instanceof Number n ? n.longValue() : null;
        return changesInWindow(projectId, releaseId, appId, curr);
    }

    private List<Map<String, Object>> changesInWindow(Long projectId, Long releaseId, Long appId, OffsetDateTime curr) {
        OffsetDateTime prev = previousReleasedAt(projectId, releaseId, appId, curr);
        String appFilter = appId != null ? " AND app_id = ? " : "";
        String sql = """
                SELECT event_id FROM change_event
                WHERE project_id = ? AND event_type IN ('CODE_COMMIT','MR_MERGED')
                  AND occurred_at > ? AND occurred_at <= ?
                """ + appFilter + " ORDER BY occurred_at";
        List<Object> args = new ArrayList<>(List.of(projectId));
        args.add(prev != null ? prev : curr.minusDays(14));
        args.add(curr);
        if (appId != null) {
            args.add(appId);
        }
        return jdbc.queryForList(sql, args.toArray());
    }

    private int countChangesInWindow(Long projectId, Long releaseId) {
        return countInWindow(projectId, releaseId);
    }

    private OffsetDateTime previousReleasedAt(Long projectId, Long releaseId, Long appId, OffsetDateTime curr) {
        String appFilter = appId != null ? " AND app_id = ? " : "";
        String sql = """
                SELECT released_at FROM release
                WHERE project_id = ? AND id <> ? AND released_at < ?
                """ + appFilter + " ORDER BY released_at DESC LIMIT 1";
        List<Object> args = new ArrayList<>(List.of(projectId, releaseId, curr));
        if (appId != null) {
            args.add(appId);
        }
        try {
            return jdbc.queryForObject(sql, OffsetDateTime.class, args.toArray());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    private int countInWindow(Long projectId, Long releaseId) {
        Map<String, Object> rel = jdbc.queryForMap("SELECT * FROM release WHERE id = ? AND project_id = ?",
                releaseId, projectId);
        OffsetDateTime curr = (OffsetDateTime) rel.get("released_at");
        Long appId = rel.get("app_id") instanceof Number n ? n.longValue() : null;
        OffsetDateTime prev = previousReleasedAt(projectId, releaseId, appId, curr);
        String appFilter = appId != null ? " AND app_id = ? " : "";
        String sql = """
                SELECT count(*) FROM change_event
                WHERE project_id = ? AND event_type IN ('CODE_COMMIT','MR_MERGED')
                  AND occurred_at > ? AND occurred_at <= ?
                """ + appFilter;
        List<Object> args = new ArrayList<>(List.of(projectId));
        args.add(prev != null ? prev : curr.minusDays(14));
        args.add(curr);
        if (appId != null) {
            args.add(appId);
        }
        Integer c = jdbc.queryForObject(sql, Integer.class, args.toArray());
        return c != null ? c : 0;
    }

    /** 刷新需求的 e2e_trace.trace_path（先删后插）。 */
    public void rebuildRequirement(Long projectId, Long requirementId) {
        List<Map<String, Object>> path = new ArrayList<>();
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT id, title, req_key FROM requirement WHERE id = ? AND project_id = ?",
                requirementId, projectId);
        path.add(Map.of("type", "REQUIREMENT", "id", req.get("id"),
                "title", req.get("title"), "reqKey", req.get("req_key")));

        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT c.event_id, c.event_type, c.occurred_at
                FROM artifact_link al JOIN change_event c ON c.event_id = al.from_id
                WHERE al.project_id = ? AND al.from_type = 'CHANGE_EVENT'
                  AND al.to_type = 'REQUIREMENT' AND al.to_id = ?
                  AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                ORDER BY c.occurred_at
                """, projectId, String.valueOf(requirementId));
        for (Map<String, Object> ch : changes) {
            path.add(Map.of("type", "CHANGE", "id", ch.get("event_id"),
                    "eventType", ch.get("event_type"), "time", String.valueOf(ch.get("occurred_at"))));
        }
        List<Map<String, Object>> tcs = jdbc.queryForList("SELECT id, title FROM test_case WHERE requirement_id = ?", requirementId);
        for (Map<String, Object> tc : tcs) {
            path.add(Map.of("type", "TEST_CASE", "id", tc.get("id"), "title", tc.get("title")));
        }
        List<Map<String, Object>> bugs = jdbc.queryForList(
                "SELECT id, title, status FROM bug_ticket WHERE requirement_id = ?", requirementId);
        for (Map<String, Object> b : bugs) {
            path.add(Map.of("type", "BUG", "id", b.get("id"), "title", b.get("title"), "status", b.get("status")));
        }

        String pathJson;
        try {
            pathJson = mapper.writeValueAsString(path);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 trace_path 失败", e);
        }
        jdbc.update("DELETE FROM e2e_trace WHERE project_id = ? AND requirement_id = ?", projectId, requirementId);
        jdbc.update("""
                INSERT INTO e2e_trace(project_id, requirement_id, trace_path)
                VALUES (?, ?, ?::jsonb)
                """, projectId, requirementId, pathJson);
    }
}