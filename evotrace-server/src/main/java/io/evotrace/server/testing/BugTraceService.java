package io.evotrace.server.testing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Bug → Commit → Files → Test Cases traceability for QA.
 * Links bugs to the changes that fixed them and the changes that introduced them.
 */
@Service
public class BugTraceService {

    private static final Logger log = LoggerFactory.getLogger(BugTraceService.class);
    private final JdbcTemplate jdbc;

    public BugTraceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        jdbc.update("""
                INSERT INTO bug_ticket(project_id, requirement_id, title, description, severity,
                    status, found_by, found_version, assigned_to)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, projectId, data.get("requirementId"), data.get("title"),
                data.getOrDefault("description", ""), data.getOrDefault("severity", "P2"),
                data.get("foundBy"), data.get("foundVersion"), data.get("assignedTo"));

        // Auto-link to recent changes on the same requirement
        if (data.containsKey("requirementId")) {
            try {
                jdbc.update("""
                        INSERT INTO bug_change_link(bug_id, change_event_id, link_type)
                        SELECT currval('bug_ticket_id_seq'), c.event_id, 'INTRODUCE'
                        FROM change_event c
                        JOIN iteration i ON i.id = c.iteration_id
                        JOIN requirement r ON r.iteration_id = i.id
                        WHERE r.id = ?
                        ORDER BY c.occurred_at DESC LIMIT 5
                        """, ((Number) data.get("requirementId")).longValue());
            } catch (Exception e) {
                log.debug("auto-link skipped: {}", e.getMessage());
            }
        }
        return Map.of("success", true);
    }
}
