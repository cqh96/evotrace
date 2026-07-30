package io.evotrace.server.file;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * File-level evolution history: which versions changed a given file and why.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileHistoryController {

    private final JdbcTemplate jdbcTemplate;

    public FileHistoryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam String path,
                                                      @RequestParam String projectKey) {
        return Result.ok(jdbcTemplate.queryForList("""
                SELECT c.event_id AS "eventId", c.event_type AS "eventType",
                       c.commit_sha AS "commitSha", c.author, c.branch,
                       c.occurred_at AS "occurredAt",
                       f.change_kind AS "changeKind",
                       f.add_lines AS "addLines", f.del_lines AS "delLines",
                       s.content AS summary
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE f.file_path LIKE ?
                ORDER BY c.occurred_at DESC
                LIMIT 50
                """, projectKey, "%" + path + "%"));
    }
}
