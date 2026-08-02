package io.evotrace.server.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Unified analysis API: breaking change detection, impact analysis,
 * risk scoring, and hotspot analysis.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/analysis")
public class AnalysisController {

    private final JdbcTemplate jdbc;
    private final BreakingChangeDetector breakingChangeDetector;
    private final ImpactAnalyzer impactAnalyzer;
    private final RiskScorer riskScorer;
    private final HotspotAnalyzer hotspotAnalyzer;

    public AnalysisController(JdbcTemplate jdbc, BreakingChangeDetector breakingChangeDetector,
                               ImpactAnalyzer impactAnalyzer, RiskScorer riskScorer,
                               HotspotAnalyzer hotspotAnalyzer) {
        this.jdbc = jdbc;
        this.breakingChangeDetector = breakingChangeDetector;
        this.impactAnalyzer = impactAnalyzer;
        this.riskScorer = riskScorer;
        this.hotspotAnalyzer = hotspotAnalyzer;
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Get breaking change alerts for a project */
    @GetMapping("/breaking-changes")
    public Result<List<Map<String, Object>>> breakingChanges(@PathVariable String projectKey) {
        Long projectId = getProjectId(projectKey);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, change_type AS "changeType", severity, detail_json::text AS detail,
                       acknowledged, created_at AS "createdAt"
                FROM breaking_change_alert
                WHERE project_id = ? AND acknowledged = false
                ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, created_at DESC
                LIMIT 50
                """, projectId);
        // jsonb → text, then parse back so the frontend gets a plain object
        for (Map<String, Object> row : rows) {
            try {
                row.put("detail", mapper.readValue((String) row.get("detail"), Map.class));
            } catch (Exception e) {
                // keep the raw text on parse failure
            }
        }
        return Result.ok(rows);
    }

    /** Acknowledge a breaking change alert */
    @PostMapping("/breaking-changes/{alertId}/acknowledge")
    public Result<Void> acknowledge(@PathVariable String projectKey, @PathVariable Long alertId) {
        jdbc.update("UPDATE breaking_change_alert SET acknowledged = true WHERE id = ?", alertId);
        return Result.ok(null);
    }

    /** Run impact analysis for a set of changed APIs */
    @GetMapping("/impact")
    public Result<Map<String, Object>> impact(@PathVariable String projectKey,
                                               @RequestParam String fromVersion,
                                               @RequestParam String toVersion) {
        Long projectId = getProjectId(projectKey);
        List<String> changedApis = jdbc.queryForList("""
                SELECT si.identity_key
                FROM snapshot_item si
                JOIN snapshot_item_ref r ON r.item_hash = si.content_hash
                JOIN snapshot s ON s.id = r.snapshot_id
                JOIN release rel ON rel.id = s.release_id
                WHERE rel.project_id = ? AND rel.version = ?
                  AND si.category = 'API' AND r.change_flag IN ('ADDED', 'MODIFIED', 'REMOVED')
                LIMIT 100
                """, projectId, toVersion).stream()
                .map(m -> (String) m.get("identity_key"))
                .toList();
        return Result.ok(impactAnalyzer.analyze(projectId, changedApis));
    }

    /** Calculate release risk score */
    @GetMapping("/risk-score")
    public Result<Map<String, Object>> riskScore(@PathVariable String projectKey,
                                                   @RequestParam String fromVersion,
                                                   @RequestParam String toVersion) {
        Long projectId = getProjectId(projectKey);
        // Get release ID for the target version
        Map<String, Object> release = jdbc.queryForMap(
                "SELECT id, released_at FROM release WHERE project_id = ? AND version = ?",
                projectId, toVersion);
        Long releaseId = ((Number) release.get("id")).longValue();
        java.sql.Timestamp ts = (java.sql.Timestamp) release.get("released_at");
        return Result.ok(riskScorer.score(releaseId, projectId, fromVersion, toVersion,
                ts.toInstant().atOffset(java.time.ZoneOffset.UTC)));
    }

    /** Get risk score history for a project */
    @GetMapping("/risk-score/history")
    public Result<List<Map<String, Object>>> riskScoreHistory(@PathVariable String projectKey) {
        Long projectId = getProjectId(projectKey);
        return Result.ok(jdbc.queryForList("""
                SELECT rel.version, rs.total_score AS "totalScore", rs.explanation,
                       rs.created_at AS "createdAt"
                FROM release_risk_score rs
                JOIN release rel ON rel.id = rs.release_id
                WHERE rel.project_id = ?
                ORDER BY rs.created_at DESC
                """, projectId));
    }

    /** Code hotspot analysis */
    @GetMapping("/hotspots")
    public Result<Map<String, Object>> hotspots(@PathVariable String projectKey,
                                                 @RequestParam(defaultValue = "30") int days) {
        Long projectId = getProjectId(projectKey);
        return Result.ok(Map.of(
                "topChangedFiles", hotspotAnalyzer.topChangedFiles(projectId, days, 15),
                "bugProneFiles", hotspotAnalyzer.bugProneFiles(projectId, days, 10),
                "coChangedFiles", hotspotAnalyzer.coChangedFiles(projectId, days, 10),
                "moduleHotspots", hotspotAnalyzer.moduleHotspots(projectId, days)
        ));
    }

    /** Top N most impactful endpoints */
    @GetMapping("/top-impact-endpoints")
    public Result<List<Map<String, Object>>> topImpactEndpoints(@PathVariable String projectKey,
                                                                  @RequestParam(defaultValue = "10") int limit) {
        Long projectId = getProjectId(projectKey);
        return Result.ok(impactAnalyzer.topImpactEndpoints(projectId, limit));
    }

    /** Record a dependency edge (called by SDK or manual registration) */
    @PostMapping("/dependencies")
    public Result<Void> recordDependency(@PathVariable String projectKey,
                                          @RequestBody Map<String, String> body) {
        Long projectId = getProjectId(projectKey);
        impactAnalyzer.recordDependency(projectId,
                body.get("caller"), body.get("callee"),
                body.getOrDefault("callType", "REST"));
        return Result.ok(null);
    }

    private Long getProjectId(String projectKey) {
        return jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?",
                Long.class, projectKey);
    }
}
