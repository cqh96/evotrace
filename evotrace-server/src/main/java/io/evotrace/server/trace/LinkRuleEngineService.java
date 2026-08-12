package io.evotrace.server.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对 commit message / MR title / branch 应用 project_link_rule，建立 IMPLEMENTS 边。
 * <p>
 * 受两级开关控制：{@code evotrace.trace.v2.enabled}（默认 false）与
 * project_trace_setting.auto_link_enabled。
 */
@Service
public class LinkRuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(LinkRuleEngineService.class);

    private final JdbcTemplate jdbc;
    private final ArtifactLinkService linkService;

    /** 特性开关：v2 Trace 自动建边，默认关闭。 */
    @Value("${evotrace.trace.v2.enabled:false}")
    private boolean enabled;

    public LinkRuleEngineService(JdbcTemplate jdbc, ArtifactLinkService linkService) {
        this.jdbc = jdbc;
        this.linkService = linkService;
    }

    /** 提交事件：对 message 应用 COMMIT_MESSAGE 规则、对 branch 应用 BRANCH_NAME 规则。 */
    public void onCommit(Long projectId, String eventId, String message, String branch, String author) {
        if (!shouldRun(projectId)) {
            return;
        }
        for (Map<String, Object> rule : enabledRules(projectId)) {
            String applyTo = str(rule, "apply_to");
            if ("COMMIT_MESSAGE".equals(applyTo)) {
                applyRule(projectId, rule, message, eventId, "AUTO_COMMIT_KEY", author);
            } else if ("BRANCH_NAME".equals(applyTo)) {
                applyRule(projectId, rule, branch, eventId, "AUTO_BRANCH", author);
            }
        }
    }

    /** MR 合并事件：对 title 应用 MR_TITLE 规则、对 branch 应用 BRANCH_NAME 规则。 */
    public void onMr(Long projectId, String eventId, String title, String branch, String author) {
        if (!shouldRun(projectId)) {
            return;
        }
        for (Map<String, Object> rule : enabledRules(projectId)) {
            String applyTo = str(rule, "apply_to");
            if ("MR_TITLE".equals(applyTo)) {
                applyRule(projectId, rule, title, eventId, "AUTO_MR", author);
            } else if ("BRANCH_NAME".equals(applyTo)) {
                applyRule(projectId, rule, branch, eventId, "AUTO_BRANCH", author);
            }
        }
    }

    /**
     * 仅提取命中键（不建边）——供悬空键即时检测复用。
     * @param applyTo 为空表示不过滤 apply_to。
     */
    public List<String> extractKeys(Long projectId, String text, String applyTo) {
        List<String> keys = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return keys;
        }
        for (Map<String, Object> rule : enabledRules(projectId)) {
            if (applyTo != null && !applyTo.equals(str(rule, "apply_to"))) {
                continue;
            }
            String pattern = str(rule, "pattern");
            String extractGroup = str(rule, "extract_group");
            if (extractGroup == null || extractGroup.isBlank()) {
                extractGroup = "reqKey";
            }
            try {
                Matcher m = Pattern.compile(pattern).matcher(text);
                while (m.find()) {
                    try {
                        String key = m.group(extractGroup);
                        if (key != null && !key.isBlank()) {
                            keys.add(key);
                        }
                    } catch (IllegalArgumentException ignore) {
                        // 命名组缺失，跳过
                    }
                }
            } catch (Exception ignore) {
                // 规则正则无法编译，跳过
            }
        }
        return keys;
    }

    private boolean shouldRun(Long projectId) {
        if (!enabled) {
            return false;
        }
        try {
            Boolean auto = jdbc.queryForObject(
                    "SELECT auto_link_enabled FROM project_trace_setting WHERE project_id = ?",
                    Boolean.class, projectId);
            return auto == null || auto;
        } catch (EmptyResultDataAccessException e) {
            return true;
        }
    }

    private List<Map<String, Object>> enabledRules(Long projectId) {
        return jdbc.queryForList("""
                SELECT * FROM project_link_rule
                WHERE project_id = ? AND enabled = TRUE
                ORDER BY priority ASC
                """, projectId);
    }

    private void applyRule(Long projectId, Map<String, Object> rule, String text,
                           String eventId, String source, String author) {
        if (text == null || text.isBlank()) {
            return;
        }
        String pattern = str(rule, "pattern");
        String extractGroup = str(rule, "extract_group");
        if (extractGroup == null || extractGroup.isBlank()) {
            extractGroup = "reqKey";
        }
        Pattern p;
        try {
            p = Pattern.compile(pattern);
        } catch (Exception e) {
            return;
        }
        Matcher m = p.matcher(text);
        while (m.find()) {
            String key;
            try {
                key = m.group(extractGroup);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (key == null || key.isBlank()) {
                continue;
            }
            Map<String, Object> req = findRequirement(projectId, key.trim().toUpperCase());
            if (req == null) {
                continue; // 悬空键：不建边，由治理查询即时检测
            }
            try {
                int confidence = rule.get("confidence") instanceof Number n ? n.intValue() : 90;
                linkService.upsertAuto(projectId, "CHANGE_EVENT", eventId,
                        "REQUIREMENT", String.valueOf(req.get("id")), "IMPLEMENTS",
                        confidence, source, Map.of(
                                "matchedText", m.group(),
                                "applyTo", str(rule, "apply_to"),
                                "eventId", eventId));
            } catch (Exception e) {
                log.warn("auto link skipped for event {}: {}", eventId, e.getMessage());
            }
        }
    }

    private Map<String, Object> findRequirement(Long projectId, String reqKey) {
        try {
            return jdbc.queryForMap(
                    "SELECT id, req_key FROM requirement WHERE project_id = ? AND lower(req_key) = lower(?)",
                    projectId, reqKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}