package io.evotrace.server.testplan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test case management: module tree (parent_id), CRUD with pagination/filter,
 * linked bugs and delete protection (cases with execution history, plan items,
 * linked bugs or child nodes cannot be deleted).
 */
@Service
public class TestCaseService {

    /** Fields allowed on create/update (whitelist, data-driven style). */
    private static final Set<String> MUTABLE_FIELDS = Set.of(
            "title", "description", "steps", "test_type", "priority",
            "related_files", "related_apis", "tags", "requirement_id",
            "parent_id", "node_type");

    private final JdbcTemplate jdbc;

    public TestCaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Flat list for the frontend module tree (el-tree). */
    public List<Map<String, Object>> tree(Long projectId) {
        return jdbc.queryForList("""
                SELECT tc.id, tc.parent_id AS "parentId", tc.title, tc.node_type AS "nodeType",
                       (SELECT count(*) FROM test_case child WHERE child.parent_id = tc.id) AS "childCount"
                FROM test_case tc
                WHERE tc.project_id = ?
                ORDER BY CASE tc.node_type WHEN 'MODULE' THEN 0 ELSE 1 END, tc.id
                """, projectId);
    }

    public Map<String, Object> list(Long projectId, int page, int pageSize, String keyword,
                                    String testType, String priority, Long requirementId,
                                    Long parentId, String tag) {
        StringBuilder where = new StringBuilder(" WHERE tc.project_id = ? AND tc.node_type = 'CASE'");
        List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (tc.title ILIKE ? OR tc.tags ILIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (testType != null && !testType.isBlank()) {
            where.append(" AND tc.test_type = ?");
            args.add(testType);
        }
        if (priority != null && !priority.isBlank()) {
            where.append(" AND tc.priority = ?");
            args.add(priority);
        }
        if (requirementId != null) {
            where.append(" AND tc.requirement_id = ?");
            args.add(requirementId);
        }
        if (parentId != null) {
            where.append(" AND tc.parent_id = ?");
            args.add(parentId);
        }
        if (tag != null && !tag.isBlank()) {
            where.append(" AND tc.tags ILIKE ?");
            args.add("%" + tag + "%");
        }

        int total = jdbc.queryForObject(
                "SELECT count(*) FROM test_case tc" + where, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tc.id, tc.title, tc.test_type AS "testType", tc.priority, tc.tags,
                       tc.requirement_id AS "requirementId", tc.parent_id AS "parentId",
                       tc.updated_at AS "updatedAt",
                       (SELECT count(*) FROM test_execution te WHERE te.test_case_id = tc.id) AS "execCount",
                       (SELECT te.status FROM test_execution te WHERE te.test_case_id = tc.id
                         ORDER BY te.executed_at DESC LIMIT 1) AS "lastStatus"
                FROM test_case tc""" + where + """
                ORDER BY CASE tc.priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END, tc.id
                LIMIT ? OFFSET ?
                """, java.util.stream.Stream.concat(args.stream(), java.util.stream.Stream.of(pageSize, (page - 1) * pageSize)).toArray());

        return Map.of("total", total, "list", rows);
    }

    public Map<String, Object> detail(Long projectId, Long caseId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT tc.*, (SELECT r.title FROM requirement r WHERE r.id = tc.requirement_id) AS "requirementTitle"
                FROM test_case tc WHERE tc.id = ? AND tc.project_id = ?
                """, caseId, projectId);
        row.put("bugs", jdbc.queryForList("""
                SELECT b.id, b.title, b.severity, b.status, b.external_key AS "externalKey"
                FROM test_case_bug_link l JOIN bug_ticket b ON b.id = l.bug_id
                WHERE l.test_case_id = ? ORDER BY b.id
                """, caseId));
        return row;
    }

    @Transactional
    public Long create(Long projectId, Map<String, Object> data) {
        Map<String, Object> clean = whitelist(data);
        String title = (String) clean.getOrDefault("title", "未命名用例");
        String nodeType = (String) clean.getOrDefault("node_type", "CASE");
        clean.put("project_id", projectId);
        clean.put("title", title);
        clean.put("node_type", nodeType);

        StringBuilder cols = new StringBuilder("project_id, title, node_type");
        StringBuilder vals = new StringBuilder("?, ?, ?");
        List<Object> args = new java.util.ArrayList<>(List.of(projectId, title, nodeType));
        for (Map.Entry<String, Object> e : clean.entrySet()) {
            if (MUTABLE_FIELDS.contains(e.getKey()) && !"title".equals(e.getKey()) && !"node_type".equals(e.getKey())) {
                cols.append(", ").append(snake(e.getKey()));
                vals.append(", ?");
                args.add(e.getValue());
            }
        }
        return jdbc.queryForObject(
                "INSERT INTO test_case(" + cols + ") VALUES (" + vals + ") RETURNING id",
                Long.class, args.toArray());
    }

    @Transactional
    public void update(Long projectId, Long caseId, Map<String, Object> data) {
        Map<String, Object> clean = whitelist(data);
        if (clean.isEmpty()) return;
        StringBuilder set = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : clean.entrySet()) {
            if (set.length() > 0) set.append(", ");
            set.append(snake(e.getKey())).append(" = ?");
            args.add(e.getValue());
        }
        set.append(", updated_at = now()");
        args.add(caseId);
        args.add(projectId);
        jdbc.update("UPDATE test_case SET " + set + " WHERE id = ? AND project_id = ?", args.toArray());
    }

    @Transactional
    public void delete(Long projectId, Long caseId) {
        int execCount = jdbc.queryForObject(
                "SELECT count(*) FROM test_execution WHERE test_case_id = ?", Integer.class, caseId);
        int planCount = jdbc.queryForObject(
                "SELECT count(*) FROM test_plan_item WHERE test_case_id = ?", Integer.class, caseId);
        int bugCount = jdbc.queryForObject(
                "SELECT count(*) FROM test_case_bug_link WHERE test_case_id = ?", Integer.class, caseId);
        int childCount = jdbc.queryForObject(
                "SELECT count(*) FROM test_case WHERE parent_id = ?", Integer.class, caseId);
        if (execCount > 0 || planCount > 0 || bugCount > 0 || childCount > 0) {
            throw new IllegalArgumentException("该用例已产生执行记录/被测试计划引用/关联缺陷或有子节点，不可删除");
        }
        int updated = jdbc.update("DELETE FROM test_case WHERE id = ? AND project_id = ?", caseId, projectId);
        if (updated == 0) {
            throw new IllegalArgumentException("用例不存在");
        }
    }

    @Transactional
    public void linkBug(Long projectId, Long caseId, Long bugId) {
        jdbc.update("""
                INSERT INTO test_case_bug_link(test_case_id, bug_id) VALUES (?, ?)
                ON CONFLICT (test_case_id, bug_id) DO NOTHING
                """, caseId, bugId);
    }

    @Transactional
    public void unlinkBug(Long projectId, Long caseId, Long bugId) {
        jdbc.update("DELETE FROM test_case_bug_link WHERE test_case_id = ? AND bug_id = ?", caseId, bugId);
    }

    /** Accept camelCase input (nodeType/parentId/testType/...) and normalize to snake_case. */
    private static Map<String, Object> whitelist(Map<String, Object> data) {
        Map<String, Object> clean = new java.util.LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                String key = snake(e.getKey());
                if (MUTABLE_FIELDS.contains(key) && e.getValue() != null) {
                    clean.put(key, e.getValue());
                }
            }
        }
        return clean;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
