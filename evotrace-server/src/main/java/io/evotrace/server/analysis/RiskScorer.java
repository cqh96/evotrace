package io.evotrace.server.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Calculates a 0-100 release risk score based on:
 * - Change volume (30%): total files changed, lines modified
 * - Breaking changes (25%): detected critical/warning breaks
 * - Historical bugs (20%): past fix commits in changed files
 * - Impact radius (15%): number of downstream services affected
 * - Time factor (10%): Friday deployments, late hours
 */
@Component
public class RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(RiskScorer.class);

    private final JdbcTemplate jdbc;
    private final BreakingChangeDetector breakingChangeDetector;
    private final ImpactAnalyzer impactAnalyzer;

    public RiskScorer(JdbcTemplate jdbc, BreakingChangeDetector breakingChangeDetector,
                      ImpactAnalyzer impactAnalyzer) {
        this.jdbc = jdbc;
        this.breakingChangeDetector = breakingChangeDetector;
        this.impactAnalyzer = impactAnalyzer;
    }

    /**
     * Calculate the risk score for a release relative to its predecessor.
     */
    public Map<String, Object> score(Long releaseId, Long projectId, String fromVersion, String toVersion,
                                      OffsetDateTime releaseTime) {
        // 1. Change volume (30 points)
        int volumeScore = calculateVolumeScore(projectId, fromVersion, toVersion);

        // 2. Breaking changes (25 points)
        int breakingScore = calculateBreakingScore(projectId, fromVersion, toVersion);

        // 3. Historical bugs (20 points)
        int bugScore = calculateBugScore(projectId, fromVersion, toVersion);

        // 4. Impact radius (15 points)
        int impactScore = calculateImpactScore(projectId, fromVersion, toVersion);

        // 5. Time factor (10 points)
        int timeScore = calculateTimeScore(releaseTime);

        int totalScore = volumeScore + breakingScore + bugScore + impactScore + timeScore;
        totalScore = Math.min(100, Math.max(0, totalScore));

        // Generate human-readable explanation
        String riskLevel = totalScore >= 70 ? "高风险" : totalScore >= 40 ? "中风险" : "低风险";
        String explanation = buildExplanation(totalScore, riskLevel, volumeScore, breakingScore,
                bugScore, impactScore, timeScore);

        // Persist
        jdbc.update("""
                INSERT INTO release_risk_score(release_id, total_score, change_volume, breaking_change,
                    historical_bugs, impact_radius, time_factor, explanation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (release_id) DO UPDATE SET
                    total_score = EXCLUDED.total_score,
                    explanation = EXCLUDED.explanation
                """, releaseId, totalScore, volumeScore, breakingScore, bugScore, impactScore, timeScore, explanation);

        log.info("risk score for release {}: {}/100 ({})", toVersion, totalScore, riskLevel);

        return Map.of(
                "totalScore", totalScore,
                "riskLevel", riskLevel,
                "subScores", Map.of(
                        "changeVolume", volumeScore,
                        "breakingChange", breakingScore,
                        "historicalBugs", bugScore,
                        "impactRadius", impactScore,
                        "timeFactor", timeScore
                ),
                "explanation", explanation
        );
    }

    private int calculateVolumeScore(Long projectId, String from, String to) {
        try {
            Map<String, Object> stats = jdbc.queryForMap("""
                    SELECT count(DISTINCT c.id) AS commits,
                           coalesce(sum(f.add_lines + f.del_lines), 0) AS total_lines
                    FROM change_event c
                    JOIN release f_rel ON f_rel.project_id = c.project_id AND f_rel.version = ?
                    JOIN release t_rel ON t_rel.project_id = c.project_id AND t_rel.version = ?
                    LEFT JOIN change_file f ON f.event_id = c.event_id
                    WHERE c.project_id = ? AND c.occurred_at > f_rel.released_at AND c.occurred_at <= t_rel.released_at
                    """, from, to, projectId);

            long commits = ((Number) stats.get("commits")).longValue();
            long lines = ((Number) stats.get("total_lines")).longValue();

            // Scale: <5 commits = 5pts, 5-20 = 15pts, 20-50 = 22pts, >50 = 30pts
            if (commits <= 5) return 5;
            if (commits <= 20) return 15;
            if (commits <= 50) return 22;
            return 30;
        } catch (Exception e) {
            return 10;
        }
    }

    private int calculateBreakingScore(Long projectId, String from, String to) {
        List<Map<String, Object>> alerts = breakingChangeDetector.detect(projectId, from, to);
        long critical = alerts.stream().filter(a -> "CRITICAL".equals(a.get("severity"))).count();
        long warnings = alerts.stream().filter(a -> "WARNING".equals(a.get("severity"))).count();

        // Critical = 15pts each, Warning = 5pts each, max 25
        int score = (int) (critical * 15 + warnings * 5);
        return Math.min(25, score);
    }

    private int calculateBugScore(Long projectId, String from, String to) {
        try {
            Integer bugCommits = jdbc.queryForObject("""
                    SELECT count(DISTINCT c.id)
                    FROM change_event c
                    JOIN release f_rel ON f_rel.project_id = c.project_id AND f_rel.version = ?
                    JOIN release t_rel ON t_rel.project_id = c.project_id AND t_rel.version = ?
                    LEFT JOIN ai_semantic_unit s ON s.target_type = 'CHANGE_EVENT'
                        AND s.target_id = c.event_id AND s.kind = 'SUMMARY'
                    WHERE c.project_id = ?
                      AND c.occurred_at > f_rel.released_at
                      AND c.occurred_at <= t_rel.released_at
                      AND (c.event_type = 'BUGFIX'
                           OR s.content ILIKE '%fix%'
                           OR s.content ILIKE '%bug%'
                           OR s.content ILIKE '%修复%')
                    """, Integer.class, from, to, projectId);

            if (bugCommits == null || bugCommits == 0) return 0;
            if (bugCommits <= 3) return 8;
            if (bugCommits <= 10) return 14;
            return 20;
        } catch (Exception e) {
            return 5;
        }
    }

    private int calculateImpactScore(Long projectId, String from, String to) {
        try {
            // Find changed APIs and analyze impact
            List<String> changedApis = jdbc.queryForList("""
                    SELECT si.identity_key
                    FROM snapshot_item si
                    JOIN snapshot_item_ref r ON r.item_hash = si.content_hash
                    JOIN snapshot s ON s.id = r.snapshot_id
                    JOIN release rel ON rel.id = s.release_id
                    WHERE rel.project_id = ? AND rel.version = ?
                      AND si.category = 'API' AND r.change_flag IN ('ADDED', 'MODIFIED')
                    LIMIT 50
                    """, projectId, to).stream()
                    .map(m -> (String) m.get("identity_key"))
                    .toList();

            if (changedApis.isEmpty()) return 3;

            Map<String, Object> impact = impactAnalyzer.analyze(projectId, changedApis);
            int affected = ((Number) impact.get("affectedNodeCount")).intValue();

            if (affected <= 2) return 3;
            if (affected <= 10) return 8;
            return 15;
        } catch (Exception e) {
            return 5;
        }
    }

    private int calculateTimeScore(OffsetDateTime time) {
        if (time == null) return 5;
        LocalDate localDate = time.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        DayOfWeek day = localDate.getDayOfWeek();
        int hour = time.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).getHour();

        // Friday afternoon or weekend → higher risk
        if (day == DayOfWeek.FRIDAY && hour >= 16) return 10;
        if (day == DayOfWeek.FRIDAY) return 7;
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return 10;
        // Late night deployment
        if (hour >= 21 || hour <= 2) return 6;
        return 2;
    }

    private String buildExplanation(int total, String level, int vol, int brk, int bug, int imp, int time) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(level).append(" ").append(total).append("/100】");
        if (brk >= 15) sb.append("存在严重破坏性变更。");
        if (vol >= 20) sb.append("变更量较大。");
        if (bug >= 14) sb.append("涉及较多历史缺陷修复文件。");
        if (imp >= 12) sb.append("影响面较广，建议全量回归。");
        if (time >= 7) sb.append("发布时间窗口风险较高。");
        return sb.toString();
    }
}
