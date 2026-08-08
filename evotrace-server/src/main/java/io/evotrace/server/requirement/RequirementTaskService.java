package io.evotrace.server.requirement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求子任务（分配需求任务）：负责人/状态/工时/优先级，支持排序。
 */
@Service
public class RequirementTaskService {

    private static final Set<String> MUTABLE_FIELDS = Set.of(
            "title", "assignee", "estimateHours", "priority");
    private static final Set<String> STATUSES = Set.of("TODO", "DOING", "DONE");

    private final JdbcTemplate jdbc;

    public RequirementTaskService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(Long requirementId) {
        return jdbc.queryForList("""
                SELECT id, title, assignee, status, estimate_hours AS "estimateHours",
                       priority, sort_order AS "sortOrder", created_at AS "createdAt"
                FROM requirement_task WHERE requirement_id = ?
                ORDER BY sort_order, id
                """, requirementId);
    }

    @Transactional
    public Map<String, Object> create(Long requirementId, Map<String, Object> data) {
        String title = data.get("title") != null ? data.get("title").toString() : null;
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("任务标题不能为空");
        }
        Integer maxOrder = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM requirement_task WHERE requirement_id = ?",
                Integer.class, requirementId);
        Long id = jdbc.queryForObject("""
                INSERT INTO requirement_task(requirement_id, title, assignee, status,
                    estimate_hours, priority, sort_order)
                VALUES (?, ?, ?, 'TODO', ?, ?, ?)
                RETURNING id
                """, Long.class, requirementId, title, data.get("assignee"),
                data.get("estimateHours"), data.getOrDefault("priority", "P2"), maxOrder + 10);
        return Map.of("id", id);
    }

    @Transactional
    public void update(Long requirementId, Long taskId, Map<String, Object> data) {
        StringBuilder set = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (MUTABLE_FIELDS.contains(e.getKey())) {
                if (set.length() > 0) {
                    set.append(", ");
                }
                set.append(snake(e.getKey())).append(" = ?");
                args.add(e.getValue());
            }
        }
        if (set.length() == 0) {
            return;
        }
        set.append(", updated_at = now()");
        args.add(taskId);
        args.add(requirementId);
        int updated = jdbc.update("UPDATE requirement_task SET " + set
                + " WHERE id = ? AND requirement_id = ?", args.toArray());
        if (updated == 0) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
    }

    @Transactional
    public void updateStatus(Long requirementId, Long taskId, String status) {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("非法任务状态: " + status);
        }
        int updated = jdbc.update("UPDATE requirement_task SET status = ?, updated_at = now() "
                + "WHERE id = ? AND requirement_id = ?", status, taskId, requirementId);
        if (updated == 0) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
    }

    @Transactional
    public void reorder(Long requirementId, List<Map<String, Object>> order) {
        for (Map<String, Object> item : order) {
            Object idObj = item.get("id");
            Object orderObj = item.get("sortOrder");
            if (idObj == null) {
                continue;
            }
            jdbc.update("UPDATE requirement_task SET sort_order = ?, updated_at = now() "
                            + "WHERE id = ? AND requirement_id = ?",
                    orderObj != null ? ((Number) orderObj).intValue() : 0,
                    ((Number) idObj).longValue(), requirementId);
        }
    }

    @Transactional
    public void delete(Long requirementId, Long taskId) {
        int updated = jdbc.update("DELETE FROM requirement_task WHERE id = ? AND requirement_id = ?",
                taskId, requirementId);
        if (updated == 0) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
    }

    private static String snake(String key) {
        // camelCase → snake_case（本服务字段有限，直接映射）
        return switch (key) {
            case "estimateHours" -> "estimate_hours";
            default -> key;
        };
    }
}
