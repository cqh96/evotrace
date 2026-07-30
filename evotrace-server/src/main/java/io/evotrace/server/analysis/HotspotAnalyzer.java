package io.evotrace.server.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Identifies code hotspots — modules/files that change frequently,
 * have high bug-fix ratios, or are frequently co-changed.
 */
@Component
public class HotspotAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(HotspotAnalyzer.class);

    private final JdbcTemplate jdbc;

    public HotspotAnalyzer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Top changed files in the last N days.
     */
    public List<Map<String, Object>> topChangedFiles(Long projectId, int days, int limit) {
        return jdbc.queryForList("""
                SELECT f.file_path AS "filePath",
                       count(DISTINCT c.event_id) AS "changeCount",
                       count(DISTINCT c.author) AS "authorCount",
                       max(c.occurred_at) AS "lastChanged"
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                WHERE c.project_id = ?
                  AND c.occurred_at >= now() - (? || ' days')::interval
                GROUP BY f.file_path
                ORDER BY "changeCount" DESC
                LIMIT ?
                """, projectId, String.valueOf(days), limit);
    }

    /**
     * Files with the highest bug-fix ratio (bug fixes / total changes).
     */
    public List<Map<String, Object>> bugProneFiles(Long projectId, int days, int limit) {
        return jdbc.queryForList("""
                SELECT f.file_path AS "filePath",
                       count(DISTINCT c.event_id) AS "totalChanges",
                       count(DISTINCT CASE WHEN s.content ILIKE '%fix%'
                           OR s.content ILIKE '%bug%'
                           OR s.content ILIKE '%修复%'
                           OR s.content ILIKE '%缺陷%'
                           THEN c.event_id END) AS "bugFixes"
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE c.project_id = ? AND c.occurred_at >= now() - (? || ' days')::interval
                GROUP BY f.file_path
                HAVING count(DISTINCT c.event_id) >= 3
                ORDER BY count(DISTINCT CASE WHEN s.content ILIKE '%fix%'
                    OR s.content ILIKE '%bug%'
                    OR s.content ILIKE '%修复%'
                    OR s.content ILIKE '%缺陷%'
                    THEN c.event_id END) * 1.0 / count(DISTINCT c.event_id) DESC
                LIMIT ?
                """, projectId, String.valueOf(days), limit);
    }

    /**
     * Files that are frequently changed together (co-change coupling).
     */
    public List<Map<String, Object>> coChangedFiles(Long projectId, int days, int limit) {
        return jdbc.queryForList("""
                WITH co_changed AS (
                    SELECT f1.file_path AS file1, f2.file_path AS file2,
                           count(DISTINCT c.event_id) AS co_count
                    FROM change_file f1
                    JOIN change_file f2 ON f2.event_id = f1.event_id AND f2.file_path > f1.file_path
                    JOIN change_event c ON c.event_id = f1.event_id
                    WHERE c.project_id = ?
                      AND c.occurred_at >= now() - (? || ' days')::interval
                    GROUP BY f1.file_path, f2.file_path
                    HAVING count(DISTINCT c.event_id) >= 2
                )
                SELECT file1, file2, co_count AS "coCount"
                FROM co_changed
                ORDER BY "coCount" DESC
                LIMIT ?
                """, projectId, String.valueOf(days), limit);
    }

    /**
     * Module-level hotspot summary (group by top-level directory).
     */
    public List<Map<String, Object>> moduleHotspots(Long projectId, int days) {
        return jdbc.queryForList("""
                SELECT split_part(f.file_path, '/', 1) AS module,
                       count(DISTINCT c.event_id) AS "changes",
                       count(DISTINCT c.author) AS "authors",
                       round(avg(f.add_lines + f.del_lines), 1) AS "avgDiffSize"
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                WHERE c.project_id = ? AND c.occurred_at >= now() - (? || ' days')::interval
                GROUP BY module
                ORDER BY "changes" DESC
                """, projectId, String.valueOf(days));
    }
}
