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

    /** List requirements for a project with linked code/test/bug/task/doc counts */
    public List<Map<String, Object>> list(Long projectId, String status) {
        String statusFilter = status != null && !status.isBlank()
                ? " AND r.status = '" + status.replaceAll("[^A-Z_]", "") + "'" : "";
        return jdbc.queryForList("""
                SELECT r.id, r.title, r.priority, r.status, r.product_manager AS "pm",
                       r.assigned_to AS "assignee", r.target_version AS "targetVersion",
                       r.prototype_url AS "prototypeUrl",
                       r.business_value AS "businessValue", r.user_story AS "userStory",
                       r.acceptance_criteria AS "acceptanceCriteria",
                       r.estimate_days AS "estimateDays", r.tech_lead AS "techLead",
                       (SELECT count(*) FROM iteration i
                         JOIN change_event c ON c.iteration_id = i.id
                         WHERE i.id = r.iteration_id) AS "linkedCommits",
                       (SELECT count(*) FROM test_case tc WHERE tc.requirement_id = r.id) AS "testCases",
                       (SELECT count(*) FROM bug_ticket b WHERE b.requirement_id = r.id
                         AND b.status IN ('OPEN','IN_PROGRESS','REOPENED')) AS "openBugs",
                       (SELECT count(*) FROM bug_ticket b WHERE b.requirement_id = r.id) AS "totalBugs",
                       (SELECT count(*) FROM requirement_document d WHERE d.requirement_id = r.id) AS "docVersion",
                       (SELECT count(*) FROM requirement_task t WHERE t.requirement_id = r.id) AS "taskCount",
                       (SELECT count(*) FROM requirement_task t WHERE t.requirement_id = r.id
                         AND t.status = 'DONE') AS "taskDone",
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

    /** Create or update a requirement (含结构化建模字段; 创建时补初始状态历史行) */
    @Transactional
    public Map<String, Object> upsert(Long projectId, Map<String, Object> data) {
        Long id = data.containsKey("id") ? ((Number) data.get("id")).longValue() : null;
        if (id != null) {
            jdbc.update("""
                    UPDATE requirement SET title=?, description=?, priority=?, status=?,
                        business_value=?, user_story=?, acceptance_criteria=?, estimate_days=?,
                        tech_lead=?, prototype_url=?, design_url=?, product_manager=?,
                        assigned_to=?, target_version=?, updated_at=now()
                    WHERE id=? AND project_id=?
                    """, data.get("title"), data.getOrDefault("description", ""),
                    data.getOrDefault("priority", "P2"), data.getOrDefault("status", "DRAFT"),
                    data.get("businessValue"), data.get("userStory"),
                    data.get("acceptanceCriteria"), data.get("estimateDays"),
                    data.get("techLead"), data.get("prototypeUrl"), data.get("designUrl"),
                    data.get("productManager"), data.get("assignedTo"),
                    data.get("targetVersion"), id, projectId);
        } else {
            String status = (String) data.getOrDefault("status", "DRAFT");
            Long newId = jdbc.queryForObject("""
                    INSERT INTO requirement(project_id, workspace_id, title, description, priority,
                        status, business_value, user_story, acceptance_criteria, estimate_days,
                        tech_lead, prototype_url, design_url, product_manager, assigned_to,
                        target_version, iteration_id)
                    VALUES (?, (SELECT workspace_id FROM project WHERE id=?), ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        (SELECT id FROM iteration WHERE project_id=? AND external_key=? LIMIT 1))
                    RETURNING id
                    """, Long.class, projectId, projectId, data.get("title"),
                    data.getOrDefault("description", ""),
                    data.getOrDefault("priority", "P2"), status,
                    data.get("businessValue"), data.get("userStory"),
                    data.get("acceptanceCriteria"), data.get("estimateDays"),
                    data.get("techLead"), data.get("prototypeUrl"), data.get("designUrl"),
                    data.get("productManager"), data.get("assignedTo"),
                    data.get("targetVersion"), projectId,
                    data.getOrDefault("externalKey", ""));
            jdbc.update("""
                    INSERT INTO requirement_status_history(requirement_id, status, actor, entered_at)
                    VALUES (?, ?, ?, now())
                    """, newId, status, data.getOrDefault("productManager", "PM"));
            id = newId;
        }
        return Map.of("success", true, "id", id);
    }

    /** 需求状态机：DRAFT→REVIEW→DEVELOPING→TESTING→DONE（与 UI getNextStatus 一致） */
    private static final Map<String, List<String>> STATUS_FLOW = Map.of(
            "DRAFT", List.of("REVIEW"),
            "REVIEW", List.of("DEVELOPING"),
            "DEVELOPING", List.of("TESTING"),
            "TESTING", List.of("DONE"),
            "DONE", List.of());

    /** Update requirement status, record audit history, and notify QA on TESTING */
    @Transactional
    public void updateStatus(Long requirementId, String newStatus, String actor) {
        if (newStatus == null || newStatus.isBlank() || !STATUS_FLOW.containsKey(newStatus)) {
            throw new IllegalArgumentException("非法的目标状态: " + newStatus);
        }
        String current;
        try {
            current = jdbc.queryForObject(
                    "SELECT status FROM requirement WHERE id = ?", String.class, requirementId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("需求不存在: " + requirementId);
        }
        if (current.equals(newStatus)) {
            return; // 幂等
        }
        if (!STATUS_FLOW.get(current).contains(newStatus)) {
            throw new IllegalArgumentException("非法的状态流转: " + current + " → " + newStatus);
        }
        jdbc.update("UPDATE requirement SET status=?, updated_at=now() WHERE id=?", newStatus, requirementId);
        // 开区间审计：关闭旧状态行，开启新状态行
        jdbc.update("UPDATE requirement_status_history SET left_at = now() "
                + "WHERE requirement_id = ? AND left_at IS NULL AND status = ?", requirementId, current);
        jdbc.update("INSERT INTO requirement_status_history(requirement_id, status, from_status, actor, entered_at) "
                + "VALUES (?, ?, ?, ?, now())", requirementId, newStatus, current, actor);
        if ("TESTING".equals(newStatus)) {
            notifyQA(requirementId, actor);
        }
    }

    /** 需求详情：全行字段 + 文档/任务/原型派生信息（抽屉详情用） */
    public Map<String, Object> detail(Long projectId, Long requirementId) {
        return jdbc.queryForMap("""
                SELECT r.id, r.title, r.description, r.priority, r.status, r.source,
                       r.business_value AS "businessValue", r.user_story AS "userStory",
                       r.acceptance_criteria AS "acceptanceCriteria",
                       r.estimate_days AS "estimateDays", r.tech_lead AS "techLead",
                       r.prototype_url AS "prototypeUrl", r.design_url AS "designUrl",
                       r.product_manager AS "pm", r.assigned_to AS "assignee",
                       r.target_version AS "targetVersion", r.iteration_id AS "iterationId",
                       r.created_at AS "createdAt", r.updated_at AS "updatedAt",
                       (SELECT MAX(d.version) FROM requirement_document d
                         WHERE d.requirement_id = r.id) AS "docVersion",
                       (SELECT count(*) FROM requirement_task t
                         WHERE t.requirement_id = r.id) AS "taskTotal",
                       (SELECT count(*) FROM requirement_task t
                         WHERE t.requirement_id = r.id AND t.status = 'DONE') AS "taskDone",
                       (SELECT MAX(p.updated_at) FROM requirement_prototype p
                         WHERE p.requirement_id = r.id) AS "prototypeUpdatedAt",
                       (SELECT i.title FROM iteration i WHERE i.id = r.iteration_id) AS "iterationTitle"
                FROM requirement r WHERE r.id = ? AND r.project_id = ?
                """, requirementId, projectId);
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
