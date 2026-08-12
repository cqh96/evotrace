package io.evotrace.server.release;

import io.evotrace.common.Result;
import io.evotrace.server.ai.ReleaseNotesService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectKey}/releases")
public class ReleaseController {

    private final JdbcTemplate jdbcTemplate;
    private final ReleaseNotesService releaseNotesService;

    public ReleaseController(JdbcTemplate jdbcTemplate, ReleaseNotesService releaseNotesService) {
        this.jdbcTemplate = jdbcTemplate;
        this.releaseNotesService = releaseNotesService;
    }

    /** 生成 AI 发布说明（markdown），结果存入 ai_semantic_unit 并被 list 自动带出 */
    @PostMapping("/release-notes")
    public Result<Map<String, Object>> generateReleaseNotes(@PathVariable String projectKey,
                                                            @RequestBody Map<String, String> body) {
        String fromVersion = body.get("fromVersion");
        String toVersion = body.get("toVersion");
        if (fromVersion == null || toVersion == null) {
            throw new IllegalArgumentException("缺少 fromVersion/toVersion");
        }
        return Result.ok(releaseNotesService.generate(projectKey, fromVersion, toVersion));
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String projectKey) {
        return Result.ok(jdbcTemplate.queryForList("""
                SELECT r.id, r.version, r.base_commit AS "baseCommit", r.tag, r.env, r.released_at AS "releasedAt",
                       (SELECT s.content FROM ai_semantic_unit s
                         WHERE s.target_type = 'RELEASE' AND s.target_id = r.id::text AND s.kind = 'RELEASE_NOTE'
                         LIMIT 1) AS "releaseNote"
                FROM release r JOIN project p ON p.id = r.project_id
                WHERE p.project_key = ? ORDER BY r.released_at DESC
                """, projectKey));
    }
}
