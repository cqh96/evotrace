package io.evotrace.server.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes the impact radius of a set of changed files/APIs by walking
 * the API dependency graph and identifying all downstream consumers.
 */
@Component
public class ImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalyzer.class);

    private final JdbcTemplate jdbc;

    public ImpactAnalyzer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Given a list of changed API identities, traverse the dependency graph
     * to find all affected services and endpoints.
     *
     * @return map with "affectedServices", "affectedEndpoints", and "suggestedRegression" keys
     */
    public Map<String, Object> analyze(Long projectId, List<String> changedApiIdentities) {
        Set<String> allAffected = new LinkedHashSet<>();
        Set<String> affectedServices = new LinkedHashSet<>();
        Set<String> directCallers = new LinkedHashSet<>();

        // BFS to find all downstream consumers
        Deque<String> queue = new ArrayDeque<>(changedApiIdentities);
        Set<String> visited = new LinkedHashSet<>();
        int depth = 0;
        int maxDepth = 5; // prevent infinite loops in circular dependencies

        while (!queue.isEmpty() && depth < maxDepth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String current = queue.poll();
                if (!visited.add(current)) continue;

                // Find all callers of this API
                List<Map<String, Object>> callers = jdbc.queryForList(
                        "SELECT caller, callee FROM api_dependency_graph WHERE project_id = ? AND callee LIKE ?",
                        projectId, "%" + current.split(":")[0] + "%");

                for (var row : callers) {
                    String caller = (String) row.get("caller");
                    allAffected.add(current);
                    if (depth == 0) directCallers.add(caller);
                    affectedServices.add(extractServiceName(caller));
                    queue.add(caller);
                }
            }
            depth++;
        }

        // Build suggested regression scope
        List<String> regression = new ArrayList<>();
        regression.add("直接变更接口: " + changedApiIdentities.size() + " 个");
        if (!directCallers.isEmpty()) {
            regression.add("直接调用方: " + directCallers);
            regression.add("建议回归: " + affectedServices.stream().limit(10).toList());
        }
        if (allAffected.size() > 10) {
            regression.add("⚠ 影响面较广 (" + allAffected.size() + " 个节点)，建议全量回归");
        }

        log.info("impact analysis: {} changed APIs → {} affected nodes, {} services",
                changedApiIdentities.size(), allAffected.size(), affectedServices.size());

        return Map.of(
                "affectedNodeCount", allAffected.size(),
                "affectedServices", affectedServices.stream().limit(20).toList(),
                "directCallers", new ArrayList<>(directCallers),
                "suggestedRegression", regression
        );
    }

    /**
     * Upsert a dependency edge discovered by SDK or manual registration.
     */
    public void recordDependency(Long projectId, String caller, String callee, String callType) {
        try {
            jdbc.update("""
                    INSERT INTO api_dependency_graph(project_id, caller, callee, call_type)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (project_id, caller, callee) DO UPDATE SET detected_at = now()
                    """, projectId, caller, callee, callType);
        } catch (Exception e) {
            log.warn("failed to record dependency: {} -> {}", caller, callee, e.getMessage());
        }
    }

    /**
     * Find N most impactful endpoints (most callers) for hotspot analysis.
     */
    public List<Map<String, Object>> topImpactEndpoints(Long projectId, int limit) {
        return jdbc.queryForList("""
                SELECT callee AS endpoint, count(DISTINCT caller) AS "callerCount"
                FROM api_dependency_graph
                WHERE project_id = ?
                GROUP BY callee ORDER BY "callerCount" DESC LIMIT ?
                """, projectId, limit);
    }

    private String extractServiceName(String identity) {
        if (identity == null) return "unknown";
        int colon = identity.indexOf(':');
        return colon > 0 ? identity.substring(0, colon) : identity;
    }
}
