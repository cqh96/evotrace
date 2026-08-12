package io.evotrace.server.trace;

import io.evotrace.common.Result;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * artifact_link 边 CRUD / 确认 / 驳回 / 忽略 / 批量确认 / 节点邻接（docs/10 §8.4.3 / §8.4.6）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/trace")
public class TraceLinkController {

    private final JdbcTemplate jdbc;
    private final ArtifactLinkService linkService;

    public TraceLinkController(JdbcTemplate jdbc, ArtifactLinkService linkService) {
        this.jdbc = jdbc;
        this.linkService = linkService;
    }

    private Long pid(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @PostMapping("/links")
    public Result<Map<String, Object>> createLink(@PathVariable String projectKey,
                                                  @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        return Result.ok(linkService.createLink(p,
                str(body, "fromType"), str(body, "fromId"), str(body, "toType"), str(body, "toId"),
                str(body, "linkType"), intOr(body, "confidence"), str(body, "source"),
                mapOr(body, "meta"), str(body, "actor")));
    }

    @DeleteMapping("/links/{id}")
    public Result<Void> deleteLink(@PathVariable String projectKey, @PathVariable Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        linkService.delete(pid(projectKey), id, str(body, "actor"));
        return Result.ok(null);
    }

    @PostMapping("/links/{id}/confirm")
    public Result<Map<String, Object>> confirm(@PathVariable String projectKey, @PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        int n = linkService.confirm(pid(projectKey), id, str(body, "actor"));
        return Result.ok(Map.of("updated", n));
    }

    @PostMapping("/links/{id}/reject")
    public Result<Map<String, Object>> reject(@PathVariable String projectKey, @PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        int n = linkService.reject(pid(projectKey), id, str(body, "reason"), str(body, "actor"));
        return Result.ok(Map.of("updated", n));
    }

    /** 忽略未关联提交（写 trace_orphan_ignore，UNIQUE(project_id, change_event_id)）。 */
    @PostMapping("/links/ignore-orphan")
    public Result<Void> ignoreOrphan(@PathVariable String projectKey, @RequestBody Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO trace_orphan_ignore(project_id, change_event_id, reason, actor)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id, change_event_id) DO NOTHING
                """, pid(projectKey), str(body, "changeEventId"), str(body, "reason"), str(body, "actor"));
        return Result.ok(null);
    }

    @PostMapping("/links/batch-confirm")
    public Result<Map<String, Object>> batchConfirm(@PathVariable String projectKey,
                                                    @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        String actor = str(body, "actor");
        List<?> raws = body.get("ids") instanceof List ? (List<?>) body.get("ids") : List.of();
        int confirmed = 0;
        for (Object raw : raws) {
            if (raw instanceof Number n) {
                confirmed += linkService.confirm(p, n.longValue(), actor);
            }
        }
        return Result.ok(Map.of("confirmed", confirmed));
    }

    /** 节点邻接：{node, outbound, inbound}（docs/10 §8.4.6）。 */
    @GetMapping("/node/{type}/{id}")
    public Result<Map<String, Object>> node(@PathVariable String projectKey,
                                            @PathVariable String type, @PathVariable String id) {
        Long p = pid(projectKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node", nodeInfo(p, type, id));

        List<Map<String, Object>> outbound = jdbc.queryForList("""
                SELECT al.id AS "linkId", al.link_type AS "linkType",
                       al.to_type AS "toType", al.to_id AS "toId"
                FROM artifact_link al
                WHERE al.project_id = ? AND al.from_type = ? AND al.from_id = ? AND al.status = 'ACTIVE'
                """, p, type, id);
        for (Map<String, Object> l : outbound) {
            l.put("title", resolveTitle(p, str(l, "toType"), str(l, "toId")));
        }

        List<Map<String, Object>> inbound = jdbc.queryForList("""
                SELECT al.id AS "linkId", al.link_type AS "linkType",
                       al.from_type AS "fromType", al.from_id AS "fromId"
                FROM artifact_link al
                WHERE al.project_id = ? AND al.to_type = ? AND al.to_id = ? AND al.status = 'ACTIVE'
                """, p, type, id);
        for (Map<String, Object> l : inbound) {
            l.put("title", resolveTitle(p, str(l, "fromType"), str(l, "fromId")));
        }

        result.put("outbound", outbound);
        result.put("inbound", inbound);
        return Result.ok(result);
    }

    private Map<String, Object> nodeInfo(Long p, String type, String id) {
        switch (type) {
            case "REQUIREMENT":
                return jdbc.queryForMap("""
                        SELECT 'REQUIREMENT' AS type, id::text AS id, title, req_key AS "reqKey"
                        FROM requirement WHERE id = ?::bigint AND project_id = ?
                        """, id, p);
            case "CHANGE_EVENT":
                return jdbc.queryForMap("""
                        SELECT 'CHANGE_EVENT' AS type, event_id AS id, commit_message AS title
                        FROM change_event WHERE event_id = ? AND project_id = ?
                        """, id, p);
            case "RELEASE":
                return jdbc.queryForMap("""
                        SELECT 'RELEASE' AS type, id::text AS id, version AS title
                        FROM release WHERE id = ?::bigint AND project_id = ?
                        """, id, p);
            default:
                return Map.of("type", type, "id", id, "title", "");
        }
    }

    private Object resolveTitle(Long p, String type, String id) {
        if (id == null) {
            return null;
        }
        try {
            switch (type) {
                case "REQUIREMENT":
                    return jdbc.queryForObject("SELECT title FROM requirement WHERE id = ?::bigint AND project_id = ?",
                            String.class, id, p);
                case "CHANGE_EVENT":
                    return jdbc.queryForObject("SELECT commit_message FROM change_event WHERE event_id = ? AND project_id = ?",
                            String.class, id, p);
                case "RELEASE":
                    return jdbc.queryForObject("SELECT version FROM release WHERE id = ?::bigint AND project_id = ?",
                            String.class, id, p);
                case "BUG":
                    return jdbc.queryForObject("SELECT title FROM bug_ticket WHERE id = ?::bigint AND project_id = ?",
                            String.class, id, p);
                case "TEST_CASE":
                    return jdbc.queryForObject("SELECT title FROM test_case WHERE id = ?::bigint", String.class, id);
                case "ITERATION":
                    return jdbc.queryForObject("SELECT title FROM iteration WHERE id = ?::bigint", String.class, id);
                default:
                    return null;
            }
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v != null ? v.toString() : null;
    }

    private static Integer intOr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOr(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }
}