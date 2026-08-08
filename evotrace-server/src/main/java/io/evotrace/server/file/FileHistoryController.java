package io.evotrace.server.file;

import io.evotrace.common.Result;
import io.evotrace.server.ingestion.BlobStoreService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File-level evolution history: which versions changed a given file and why.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileHistoryController {

    private static final int MAX_DIFF_CHARS = 20_000;

    private final JdbcTemplate jdbcTemplate;
    private final BlobStoreService blobStore;

    public FileHistoryController(JdbcTemplate jdbcTemplate, BlobStoreService blobStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.blobStore = blobStore;
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam String path,
                                                      @RequestParam String projectKey) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.event_id AS "eventId", c.event_type AS "eventType",
                       c.commit_sha AS "commitSha", c.author, c.branch,
                       c.commit_message AS "commitMessage",
                       c.occurred_at AS "occurredAt",
                       f.file_path AS "filePath",
                       f.change_kind AS "changeKind",
                       f.add_lines AS "addLines", f.del_lines AS "delLines",
                       f.diff_blob_ref AS "diffBlobRef",
                       s.content AS summary
                FROM change_file f
                JOIN change_event c ON c.event_id = f.event_id
                JOIN project p ON p.id = c.project_id AND p.project_key = ?
                LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT'
                    AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                WHERE f.file_path LIKE ?
                ORDER BY c.occurred_at DESC
                LIMIT 50
                """, projectKey, "%" + path + "%");

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>(row);
            String blobRef = (String) row.get("diffBlobRef");
            String diff = null;
            if (blobRef != null && !blobRef.isBlank()) {
                String content = blobStore.get(blobRef);
                if (content != null && !content.isBlank()) {
                    if (content.length() > MAX_DIFF_CHARS) {
                        diff = content.substring(0, MAX_DIFF_CHARS) + "\n\n… [diff truncated]";
                    } else {
                        diff = content;
                    }
                }
            }
            item.put("diff", diff);
            item.put("hasDiff", diff != null);
            result.add(item);
        }
        return Result.ok(result);
    }
}
