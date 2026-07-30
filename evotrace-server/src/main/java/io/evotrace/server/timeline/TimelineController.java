package io.evotrace.server.timeline;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectKey}/timeline")
public class TimelineController {

    private final JdbcTemplate jdbcTemplate;

    public TimelineController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> query(@PathVariable String projectKey,
                                                   @RequestParam(required = false) String app,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(defaultValue = "100") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.event_id AS "eventId", c.event_type AS "eventType",
                       a.app_key AS "appKey", c.branch, c.commit_sha AS "commitSha",
                       c.author, c.occurred_at AS "occurredAt", c.summary_status AS "summaryStatus",
                       s.content AS summary, i.title AS "iterationTitle"
                FROM change_event c
                JOIN project p ON p.id = c.project_id
                LEFT JOIN application a ON a.id = c.app_id
                LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT' AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                LEFT JOIN iteration i ON i.id = c.iteration_id
                WHERE p.project_key = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(projectKey);
        if (app != null && !app.isBlank()) {
            sql.append(" AND a.app_key = ?");
            args.add(app);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND c.event_type = ?");
            args.add(type);
        }
        sql.append(" ORDER BY c.occurred_at DESC LIMIT ?");
        args.add(Math.min(limit, 500));
        return Result.ok(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
    }
}
