package io.evotrace.server.project;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Dashboard overview: project/app counts, recent activity, trend data.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbcTemplate;

    public DashboardController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        int projectCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project WHERE status = 'ACTIVE'", Integer.class);
        int appCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM application", Integer.class);
        int todayChanges = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM change_event WHERE occurred_at >= ?",
                Integer.class, LocalDate.now().atStartOfDay());
        int releaseCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM release", Integer.class);

        return Result.ok(Map.of(
                "projectCount", projectCount,
                "appCount", appCount,
                "todayChanges", todayChanges,
                "releaseCount", releaseCount
        ));
    }

    @GetMapping("/recent-releases")
    public Result<List<Map<String, Object>>> recentReleases() {
        return Result.ok(jdbcTemplate.queryForList("""
                SELECT r.version, a.name AS project, r.released_at AS "releasedAt",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'RELEASE' AND s.target_id = r.id::text
                           AND s.kind = 'RELEASE_NOTE' LIMIT 1) AS summary
                FROM release r
                LEFT JOIN application a ON a.id = r.app_id
                ORDER BY r.released_at DESC LIMIT 10
                """));
    }

    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend() {
        return Result.ok(jdbcTemplate.queryForList("""
                SELECT d.day,
                       coalesce(c.change_count, 0) AS changes,
                       coalesce(r.release_count, 0) AS releases
                FROM generate_series(
                    date_trunc('day', now()) - interval '6 days',
                    date_trunc('day', now()),
                    interval '1 day'
                ) AS d(day)
                LEFT JOIN (
                    SELECT date_trunc('day', occurred_at) AS day, count(*) AS change_count
                    FROM change_event
                    WHERE occurred_at >= now() - interval '7 days'
                    GROUP BY date_trunc('day', occurred_at)
                ) c ON c.day = d.day
                LEFT JOIN (
                    SELECT date_trunc('day', released_at) AS day, count(*) AS release_count
                    FROM release
                    WHERE released_at >= now() - interval '7 days'
                    GROUP BY date_trunc('day', released_at)
                ) r ON r.day = d.day
                ORDER BY d.day
                """));
    }
}
