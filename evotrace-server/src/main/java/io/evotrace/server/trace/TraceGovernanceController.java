package io.evotrace.server.trace;

import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关联治理中心：未关联提交 / 待确认边 / 悬空键 / 断链（docs/10 §8.4.3）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/trace/governance")
public class TraceGovernanceController {

    private final JdbcTemplate jdbc;
    private final ArtifactLinkService linkService;
    private final ReqKeyService reqKeyService;
    private final LinkRuleEngineService ruleEngine;

    public TraceGovernanceController(JdbcTemplate jdbc, ArtifactLinkService linkService,
                                     ReqKeyService reqKeyService, LinkRuleEngineService ruleEngine) {
        this.jdbc = jdbc;
        this.linkService = linkService;
        this.reqKeyService = reqKeyService;
        this.ruleEngine = ruleEngine;
    }

    private Long pid(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@PathVariable String projectKey) {
        Long p = pid(projectKey);
        List<Map<String, Object>> dangling = danglingItems(p);
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> d : dangling) {
            keys.add(String.valueOf(d.get("matchedKey")));
        }
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("reqWithoutCode", countBroken(p, "reqWithoutCode"));
        broken.put("reqWithoutCase", countBroken(p, "reqWithoutCase"));
        broken.put("reqWithBlockingBugs", countBroken(p, "reqWithBlockingBugs"));
        broken.put("releaseWithoutGate", countBroken(p, "releaseWithoutGate"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unlinkedChanges", countUnlinked(p));
        result.put("pendingLinks", intOf("SELECT count(*) FROM artifact_link WHERE project_id = ? AND status = 'PENDING'", p));
        result.put("danglingKeys", keys.size());
        result.put("brokenChains", broken);
        return Result.ok(result);
    }

    @GetMapping("/unlinked-changes")
    public Result<Map<String, Object>> unlinkedChanges(@PathVariable String projectKey,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String author,
                                                       @RequestParam(required = false) String branch) {
        Long p = pid(projectKey);
        StringBuilder where = new StringBuilder("""
                WHERE c.project_id = ? AND c.event_type IN ('CODE_COMMIT','MR_MERGED')
                  AND NOT EXISTS (
                      SELECT 1 FROM artifact_link al
                      WHERE al.project_id = c.project_id AND al.from_type = 'CHANGE_EVENT'
                        AND al.from_id = c.event_id AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trace_orphan_ignore oi
                      WHERE oi.project_id = c.project_id AND oi.change_event_id = c.event_id
                  )
                """);
        List<Object> args = new ArrayList<>(List.of(p));
        if (author != null && !author.isBlank()) {
            where.append(" AND c.author = ?");
            args.add(author);
        }
        if (branch != null && !branch.isBlank()) {
            where.append(" AND c.branch = ?");
            args.add(branch);
        }
        int total = intOf("SELECT count(*) FROM change_event c " + where, args.toArray());
        int offset = Math.max(0, (page - 1) * size);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT c.event_id AS "eventId", c.commit_sha AS "commitSha",
                       c.commit_message AS message, c.author, c.branch, c.occurred_at AS "occurredAt"
                FROM change_event c
                """ + where + " ORDER BY c.occurred_at DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        for (Map<String, Object> item : items) {
            item.put("suggestedRequirements", List.of()); // A 期返回空数组
        }
        return Result.ok(Map.of("items", items, "total", total));
    }

    @GetMapping("/pending-links")
    public Result<List<Map<String, Object>>> pendingLinks(@PathVariable String projectKey) {
        Long p = pid(projectKey);
        return Result.ok(jdbc.queryForList("""
                SELECT al.id, al.from_type AS "fromType", al.from_id AS "fromId",
                       al.to_type AS "toType", al.to_id AS "toId", al.link_type AS "linkType",
                       al.confidence, al.source, al.meta
                FROM artifact_link al
                WHERE al.project_id = ? AND al.status = 'PENDING'
                ORDER BY al.confidence DESC
                """, p));
    }

    @GetMapping("/dangling-keys")
    public Result<Map<String, Object>> danglingKeys(@PathVariable String projectKey) {
        Long p = pid(projectKey);
        List<Map<String, Object>> items = danglingItems(p);
        return Result.ok(Map.of("items", items, "total", items.size()));
    }

    @PostMapping("/dangling-keys/create-requirement")
    public Result<Map<String, Object>> createRequirement(@PathVariable String projectKey,
                                                         @RequestBody Map<String, Object> body) {
        Long p = pid(projectKey);
        String matchedKey = body.get("matchedKey") != null ? body.get("matchedKey").toString() : null;
        if (matchedKey == null || matchedKey.isBlank()) {
            return Result.fail("REQ_KEY_INVALID", "缺少 matchedKey");
        }
        String normalized = reqKeyService.normalize(matchedKey);
        if (reqKeyService.exists(p, normalized)) {
            return Result.fail("REQ_KEY_DUPLICATE", "需求键已存在: " + normalized);
        }
        Long newId = jdbc.queryForObject("""
                INSERT INTO requirement(project_id, workspace_id, title, priority, status, source, req_key, external_key)
                VALUES (?, (SELECT workspace_id FROM project WHERE id = ?), ?, 'P2', 'DRAFT', 'MANUAL', ?, ?)
                RETURNING id
                """, Long.class, p, p,
                body.get("title") != null ? body.get("title").toString() : "从提交创建",
                normalized, normalized);
        String eventId = body.get("eventId") != null ? body.get("eventId").toString() : null;
        if (eventId != null) {
            linkService.createLink(p, "CHANGE_EVENT", eventId, "REQUIREMENT",
                    String.valueOf(newId), "IMPLEMENTS", 100, "MANUAL",
                    Map.of("eventId", eventId), body.get("actor") != null ? body.get("actor").toString() : null);
        }
        return Result.ok(Map.of("id", newId, "reqKey", normalized));
    }

    @GetMapping("/broken-chains")
    public Result<List<Map<String, Object>>> brokenChains(@PathVariable String projectKey,
                                                          @RequestParam(defaultValue = "reqWithoutCode") String type) {
        Long p = pid(projectKey);
        switch (type) {
            case "reqWithoutCode":
                return Result.ok(jdbc.queryForList("""
                        SELECT r.id, r.req_key AS "reqKey", r.title, r.status, r.target_version AS "targetVersion"
                        FROM requirement r WHERE r.project_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM artifact_link al
                              WHERE al.project_id = r.project_id AND al.from_type = 'CHANGE_EVENT'
                                AND al.to_type = 'REQUIREMENT' AND al.to_id = r.id::text
                                AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                          )
                        ORDER BY r.id
                        """, p));
            case "reqWithoutCase":
                return Result.ok(jdbc.queryForList("""
                        SELECT r.id, r.req_key AS "reqKey", r.title, r.status, r.target_version AS "targetVersion"
                        FROM requirement r WHERE r.project_id = ?
                          AND NOT EXISTS (SELECT 1 FROM test_case tc WHERE tc.requirement_id = r.id)
                        ORDER BY r.id
                        """, p));
            case "reqWithBlockingBugs":
                return Result.ok(jdbc.queryForList("""
                        SELECT r.id, r.req_key AS "reqKey", r.title, r.status, r.target_version AS "targetVersion"
                        FROM requirement r WHERE r.project_id = ?
                          AND EXISTS (
                              SELECT 1 FROM bug_ticket b WHERE b.requirement_id = r.id
                                AND b.severity IN ('P0','P1') AND b.status NOT IN ('CLOSED','VERIFIED')
                          )
                        ORDER BY r.id
                        """, p));
            case "releaseWithoutGate":
                return Result.ok(jdbc.queryForList("""
                        SELECT rel.id, rel.version, rel.released_at AS "releasedAt"
                        FROM release rel WHERE rel.project_id = ?
                          AND NOT EXISTS (SELECT 1 FROM quality_gate qg WHERE qg.release_id = rel.id)
                        ORDER BY rel.released_at DESC
                        """, p));
            default:
                return Result.fail("TRACE_DISABLED", "不支持的断链类型: " + type);
        }
    }

    private int countUnlinked(Long p) {
        return intOf("""
                SELECT count(*) FROM change_event c
                WHERE c.project_id = ? AND c.event_type IN ('CODE_COMMIT','MR_MERGED')
                  AND NOT EXISTS (
                      SELECT 1 FROM artifact_link al
                      WHERE al.project_id = c.project_id AND al.from_type = 'CHANGE_EVENT'
                        AND al.from_id = c.event_id AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM trace_orphan_ignore oi
                      WHERE oi.project_id = c.project_id AND oi.change_event_id = c.event_id
                  )
                """, p);
    }

    private int countBroken(Long p, String type) {
        switch (type) {
            case "reqWithoutCode":
                return intOf("""
                        SELECT count(*) FROM requirement r WHERE r.project_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM artifact_link al
                              WHERE al.project_id = r.project_id AND al.from_type = 'CHANGE_EVENT'
                                AND al.to_type = 'REQUIREMENT' AND al.to_id = r.id::text
                                AND al.link_type = 'IMPLEMENTS' AND al.status = 'ACTIVE'
                          )
                        """, p);
            case "reqWithoutCase":
                return intOf("""
                        SELECT count(*) FROM requirement r WHERE r.project_id = ?
                          AND NOT EXISTS (SELECT 1 FROM test_case tc WHERE tc.requirement_id = r.id)
                        """, p);
            case "reqWithBlockingBugs":
                return intOf("""
                        SELECT count(*) FROM requirement r WHERE r.project_id = ?
                          AND EXISTS (
                              SELECT 1 FROM bug_ticket b WHERE b.requirement_id = r.id
                                AND b.severity IN ('P0','P1') AND b.status NOT IN ('CLOSED','VERIFIED')
                          )
                        """, p);
            case "releaseWithoutGate":
                return intOf("""
                        SELECT count(*) FROM release rel WHERE rel.project_id = ?
                          AND NOT EXISTS (SELECT 1 FROM quality_gate qg WHERE qg.release_id = rel.id)
                        """, p);
            default:
                return 0;
        }
    }

    /** 悬空键即时检测：对未忽略 commit 的 message 跑规则，过滤掉 req_key 已存在的。 */
    private List<Map<String, Object>> danglingItems(Long p) {
        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT event_id, commit_message, occurred_at FROM change_event
                WHERE project_id = ? AND event_type IN ('CODE_COMMIT','MR_MERGED')
                  AND NOT EXISTS (
                      SELECT 1 FROM trace_orphan_ignore oi
                      WHERE oi.project_id = change_event.project_id AND oi.change_event_id = change_event.event_id
                  )
                ORDER BY occurred_at DESC LIMIT 200
                """, p);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> c : changes) {
            String msg = (String) c.get("commit_message");
            if (msg == null || msg.isBlank()) {
                continue;
            }
            for (String key : ruleEngine.extractKeys(p, msg, null)) {
                String normalized = key.trim().toUpperCase();
                if (normalized.isEmpty() || reqKeyService.exists(p, normalized)) {
                    continue;
                }
                items.add(Map.of("matchedKey", normalized, "eventId", c.get("event_id"),
                        "message", msg, "occurredAt", String.valueOf(c.get("occurred_at"))));
            }
        }
        return items;
    }

    private int intOf(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v != null ? v : 0;
    }
}