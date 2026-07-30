package io.evotrace.server.release;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectKey}/releases")
public class ReleaseController {

    private final JdbcTemplate jdbcTemplate;

    public ReleaseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(jdbcTemplate.queryForList("""
                SELECT r.version, r.base_commit AS "baseCommit", r.tag, r.env, r.released_at AS "releasedAt",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'RELEASE' AND s.target_id = r.id::text AND s.kind = 'RELEASE_NOTE'
                         LIMIT 1) AS "releaseNote"
                FROM release r JOIN project p ON p.id = r.project_id
                WHERE p.project_key = ? ORDER BY r.released_at DESC
                """, projectKey));
    }
}
