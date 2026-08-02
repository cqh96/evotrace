package io.evotrace.server.testing;

import io.evotrace.server.jira.JiraSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bug → Commit → Files → Test Cases traceability for QA.
 * Links bugs to the changes that fixed them and the changes that introduced them.
 * Also drives the bug lifecycle state machine and Jira bidirectional sync.
 */
@Service
public class BugTraceService {

    private static final Logger log = LoggerFactory.getLogger(BugTraceService.class);

    /** Legal bug status transitions. */
    private static final Map<String, Set<String>> BUG_TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS"),
            "IN_PROGRESS", Set.of("FIXED", "OPEN"),
            "FIXED", Set.of("VERIFIED", "REOPENED", "IN_PROGRESS"),
            "VERIFIED", Set.of("CLOSED", "REOPENED"),
            "REOPENED", Set.of("IN_PROGRESS", "FIXED"),
            "CLOSED", Set.of("REOPENED")
    );

    private final JdbcTemplate jdbc;
    private final JiraSyncService jiraSyncService;

    public BugTraceService(JdbcTemplate jdbc, JiraSyncService jiraSyncService) {
        this.jdbc = jdbc;
        this.jiraSyncService = jiraSyncService;
    }

    /** List bugs with linked change counts */
    public List<Map<String, Object>> list(Long projectId, String status, String severity) {
        StringBuilder sql = new StringBuilder("""
                SELECT b.*, string_agg(DISTINCT bl.change_event_id, ',') AS "linkedChanges",
                       count(DISTINCT bl.change_event_id) AS "changeCount"
                FROM bug_ticket b
                LEFT JOIN bug_change_link bl ON bl.bug_id = b.id
                WHERE b.project_id = ?
                """);
        if (status != null && !status.isBlank())
            sql.append(" AND b.status = '").append(status.replaceAll("[^A-Z_]", "")).append("'");
        if (severity != null && !severity.isBlank())
            sql.append(" AND b.severity = '").append(severity.replaceAll("[^A-Z0-9_]", "")).append("'");
        sql.append(" GROUP BY b.id ORDER BY CASE b.severity WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 ELSE 2 END, b.updated_at DESC");

        return jdbc.queryForList(sql.toString(), projectId);
    }

    /** Get full trace: Bug → Fix Commits → Changed Files → Affected Tests */
    public Map<String, Object> trace(Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);

        // Fix commits
        List<Map<String, Object>> fixCommits = jdbc.queryForList("""
                SELECT bl.link_type, c.event_id, c.commit_sha, c.author, c.occurred_at,
                       c.event_type, s.content AS summary
                FROM bug_change_link bl
                JOIN change_event c ON c.event_id = bl.change_event_id
                LEFT JOIN ai_semantic_unit s ON s.target_type='CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind='SUMMARY'
                WHERE bl.bug_id = ?
                ORDER BY c.occurred_at
                """, bugId);

        // Files changed by fix commits
        List<Map<String, Object>> changedFiles = jdbc.queryForList("""
                SELECT DISTINCT f.file_path, f.change_kind, f.add_lines, f.del_lines
                FROM bug_change_link bl
                JOIN change_file f ON f.event_id = bl.change_event_id
                WHERE bl.bug_id = ? AND bl.link_type = 'FIX'
                """, bugId);

        // Related test cases (by file path)
        List<Map<String, Object>> relatedTests = jdbc.queryForList("""
                SELECT DISTINCT tc.id, tc.title, tc.test_type, tc.priority
                FROM bug_change_link bl
                JOIN change_file f ON f.event_id = bl.change_event_id
                JOIN test_case tc ON tc.related_files ILIKE '%' || f.file_path || '%'
                WHERE bl.bug_id = ?
                """, bugId);

        return Map.of("bug", bug, "fixCommits", fixCommits, "changedFiles", changedFiles,
                "relatedTests", relatedTests);
    }

    /** Link a bug to a change event (fix commit) */
    @Transactional
    public void link(Long bugId, String changeEventId, String linkType) {
        jdbc.update("""
                INSERT INTO bug_change_link(bug_id, change_event_id, link_type)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, bugId, changeEventId, linkType);
    }

    /** Create a bug and attempt to auto-link to recent changes */
    @Transactional
    public Map<String, Object> createWithAutoLink(Long projectId, Map<String, Object> data) {
        Long bugId = jdbc.queryForObject("""
                INSERT INTO bug_ticket(project_id, requirement_id, title, description, severity,
                    status, found_by, found_version, assigned_to)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?) RETURNING id
                """, Long.class, projectId, data.get("requirementId"), data.get("title"),
                data.getOrDefault("description", ""), data.getOrDefault("severity", "P2"),
                data.get("foundBy"), data.get("foundVersion"), data.get("assignedTo"));

        // Auto-link to recent changes on the same requirement
        if (data.containsKey("requirementId")) {
            try {
                jdbc.update("""
                        INSERT INTO bug_change_link(bug_id, change_event_id, link_type)
                        SELECT ?, c.event_id, 'INTRODUCE'
                        FROM change_event c
                        JOIN iteration i ON i.id = c.iteration_id
                        JOIN requirement r ON r.iteration_id = i.id
                        WHERE r.id = ?
                        ORDER BY c.occurred_at DESC LIMIT 5
                        """, bugId, ((Number) data.get("requirementId")).longValue());
            } catch (Exception e) {
                log.debug("auto-link skipped: {}", e.getMessage());
            }
        }

        // Jira push is best-effort (fails gracefully when not configured)
        jiraSyncService.pushNewBug(bugId);
        return Map.of("success", true, "id", bugId);
    }

    /** 缺陷状态流转（状态机校验 + Jira 推送） */
    @Transactional
    public void transition(Long bugId, String toStatus, String fixedVersion) {
        String from = jdbc.queryForObject(
                "SELECT status FROM bug_ticket WHERE id = ?", String.class, bugId);
        if (!BUG_TRANSITIONS.getOrDefault(from, Set.of()).contains(toStatus)) {
            throw new IllegalArgumentException("非法缺陷状态流转: " + from + " → " + toStatus);
        }
        if ("FIXED".equals(toStatus) && fixedVersion != null && !fixedVersion.isBlank()) {
            jdbc.update("UPDATE bug_ticket SET status = ?, fixed_version = ?, updated_at = now() WHERE id = ?",
                    toStatus, fixedVersion, bugId);
        } else {
            jdbc.update("UPDATE bug_ticket SET status = ?, updated_at = now() WHERE id = ?", toStatus, bugId);
        }
        jiraSyncService.pushStatus(bugId, toStatus); // best-effort
    }

    /** 缺陷详情：基本信息 + 关联用例 + 关联变更追溯 */
    public Map<String, Object> detail(Long bugId) {
        Map<String, Object> bug = jdbc.queryForMap("SELECT * FROM bug_ticket WHERE id = ?", bugId);
        bug.put("linkedCases", jdbc.queryForList("""
                SELECT tc.id, tc.title, tc.priority, tc.test_type AS "testType",
                       l.link_type AS "linkType", l.created_at AS "linkedAt"
                FROM test_case_bug_link l JOIN test_case tc ON tc.id = l.test_case_id
                WHERE l.bug_id = ? ORDER BY tc.id
                """, bugId));
        bug.put("linkedChanges", jdbc.queryForList("""
                SELECT bl.link_type AS "linkType", c.event_id AS "eventId",
                       c.commit_sha AS "commitSha", c.author, c.occurred_at AS "occurredAt",
                       c.event_type AS "eventType",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                         LIMIT 1) AS summary
                FROM bug_change_link bl JOIN change_event c ON c.event_id = bl.change_event_id
                WHERE bl.bug_id = ? ORDER BY c.occurred_at
                """, bugId));
        return bug;
    }

    @Transactional
    public void linkCase(Long bugId, Long testCaseId) {
        jdbc.update("""
                INSERT INTO test_case_bug_link(test_case_id, bug_id) VALUES (?, ?)
                ON CONFLICT (test_case_id, bug_id) DO NOTHING
                """, testCaseId, bugId);
    }

    @Transactional
    public void unlinkCase(Long bugId, Long testCaseId) {
        jdbc.update("DELETE FROM test_case_bug_link WHERE test_case_id = ? AND bug_id = ?",
                testCaseId, bugId);
    }
}
