package io.evotrace.server.requirement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * PM-oriented requirement management with full traceability to code changes,
 * test cases, bugs, and releases.
 */
@Service
public class RequirementService {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);
    private final JdbcTemplate jdbc;

    public RequirementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** List requirements for a project with linked code/test/bug counts */
    public List<Map<String, Object>> list(Long projectId, String status) {
        String statusFilter = status != null && !status.isBlank()
                ? " AND r.status = '" + status.replaceAll("[^A-Z_]", "") + "'" : "";
        return jdbc.queryForList("""
                SELECT r.id, r.title, r.priority, r.status, r.product_manager AS "pm",
                       r.assigned_to AS "assignee", r.target_version AS "targetVersion",
                       r.prototype_url AS "prototypeUrl",
                       (SELECT count(*) FROM iteration i
                         JOIN change_event c ON c.iteration_id = i.id
                         WHERE i.id = r.iteration_id) AS "linkedCommits",
                       (SELECT count(*) FROM test_case tc WHERE tc.requirement_id = r.id) AS "testCases",
                       (SELECT count(*) FROM bug_ticket b WHERE b.requirement_id = r.id
                         AND b.status IN ('OPEN','IN_PROGRESS','REOPENED')) AS "openBugs",
                       (SELECT count(*) FROM bug_ticket b WHERE b.requirement_id = r.id) AS "totalBugs",
                       r.created_at AS "createdAt"
                FROM requirement r WHERE r.project_id = ?""" + statusFilter + " ORDER BY r.created_at DESC",
                projectId);
    }

    /** Get full E2E trace for a single requirement */
    public Map<String, Object> trace(Long projectId, Long requirementId) {
        // Requirement details
        Map<String, Object> req = jdbc.queryForMap("SELECT * FROM requirement WHERE id = ?", requirementId);

        // Linked code changes via iteration
        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT c.event_id, c.event_type, c.commit_sha, c.author, c.occurred_at,
                       s.content AS summary
                FROM change_event c
                JOIN iteration i ON i.id = c.iteration_id AND i.id = (
                    SELECT iteration_id FROM requirement WHERE id = ?
                )
                LEFT JOIN ai_semantic_unit s ON s.target_type='CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                ORDER BY c.occurred_at
                """, requirementId);

        // Linked test cases
        List<Map<String, Object>> testCases = jdbc.queryForList("""
                SELECT tc.*, (SELECT status FROM test_execution te
                    WHERE te.test_case_id = tc.id ORDER BY te.executed_at DESC LIMIT 1) AS lastStatus
                FROM test_case tc WHERE tc.requirement_id = ? ORDER BY tc.priority
                """, requirementId);

        // Linked bugs
        List<Map<String, Object>> bugs = jdbc.queryForList(
                "SELECT * FROM bug_ticket WHERE requirement_id = ? ORDER BY severity, status",
                requirementId);

        // Release info
        List<Map<String, Object>> releases = jdbc.queryForList("""
                SELECT rel.version, rel.released_at
                FROM release rel
                JOIN change_event c ON c.project_id = rel.project_id
                JOIN iteration i ON i.id = c.iteration_id
                JOIN requirement r ON r.iteration_id = i.id AND r.id = ?
                WHERE c.occurred_at <= rel.released_at
                ORDER BY rel.released_at
                """, requirementId);

        return Map.of("requirement", req, "changes", changes, "testCases", testCases,
                "bugs", bugs, "releases", releases);
    }

    /** Create or update a requirement */
    @Transactional
    public Map<String, Object> upsert(Long projectId, Map<String, Object> data) {
        Long id = data.containsKey("id") ? ((Number) data.get("id")).longValue() : null;
        if (id != null) {
            jdbc.update("""
                    UPDATE requirement SET title=?, description=?, priority=?, status=?,
                        prototype_url=?, design_url=?, product_manager=?, assigned_to=?,
                        target_version=?, updated_at=now()
                    WHERE id=? AND project_id=?
                    """, data.get("title"), data.getOrDefault("description", ""),
                    data.getOrDefault("priority", "P2"), data.getOrDefault("status", "DRAFT"),
                    data.get("prototypeUrl"), data.get("designUrl"),
                    data.get("productManager"), data.get("assignedTo"),
                    data.get("targetVersion"), id, projectId);
        } else {
            jdbc.update("""
                    INSERT INTO requirement(project_id, workspace_id, title, description, priority,
                        status, prototype_url, design_url, product_manager, assigned_to,
                        target_version, iteration_id)
                    VALUES (?, (SELECT workspace_id FROM project WHERE id=?), ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, (SELECT id FROM iteration WHERE project_id=? AND external_key=? LIMIT 1))
                    """, projectId, projectId, data.get("title"),
                    data.getOrDefault("description", ""),
                    data.getOrDefault("priority", "P2"), data.getOrDefault("status", "DRAFT"),
                    data.get("prototypeUrl"), data.get("designUrl"),
                    data.get("productManager"), data.get("assignedTo"),
                    data.get("targetVersion"), projectId,
                    data.getOrDefault("externalKey", ""));
        }
        return Map.of("success", true);
    }

    /** Update requirement status and notify QA */
    @Transactional
    public void updateStatus(Long requirementId, String newStatus, String actor) {
        jdbc.update("UPDATE requirement SET status=?, updated_at=now() WHERE id=?", newStatus, requirementId);
        if ("TESTING".equals(newStatus)) {
            notifyQA(requirementId, actor);
        }
    }

    private void notifyQA(Long requirementId, String actor) {
        try {
            Map<String, Object> req = jdbc.queryForMap(
                    "SELECT project_id, title FROM requirement WHERE id = ?", requirementId);
            jdbc.update("""
                    INSERT INTO pm_qa_notification(project_id, trigger_event, target_role, title, content)
                    VALUES (?, 'REQUIREMENT_CHANGED', 'QA', ?, ?)
                    """, ((Number) req.get("project_id")).longValue(),
                    "需求进入测试阶段: " + req.get("title"),
                    "请QA同学为需求「" + req.get("title") + "」准备测试用例");
        } catch (Exception e) {
            log.error("failed to notify QA", e);
        }
    }
}
