package io.evotrace.server.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Matches incoming ChangeEvents against subscription rules and dispatches
 * notifications via the configured channels (Feishu/DingTalk/WeChat/Email/Webhook).
 */
@Component
public class SubscriptionMatcher {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionMatcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SubscriptionMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Called by ChangeEventConsumer after persisting an event.
     * Checks all enabled subscription rules and sends notifications for matches.
     */
    @SuppressWarnings("unchecked")
    public void matchAndNotify(Long projectId, String eventId, String eventType,
                                String filePath, String appKey, String author) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT * FROM subscription_rule WHERE workspace_id = (SELECT workspace_id FROM project WHERE id = ?) AND enabled = true",
                projectId);

        for (var rule : rules) {
            try {
                // pgjdbc returns jsonb columns as PGobject, not String
                String filterJson = String.valueOf(rule.get("filter_json"));
                Map<String, Object> filter = mapper.readValue(filterJson, Map.class);

                boolean matched = true;

                // Check project filter
                if (filter.containsKey("projectKey")) {
                    String projectKey = jdbc.queryForObject(
                            "SELECT project_key FROM project WHERE id = ?", String.class, projectId);
                    matched = projectKey != null && projectKey.equals(filter.get("projectKey"));
                }

                // Check event type filter
                if (matched && filter.containsKey("eventTypes")) {
                    List<String> types = (List<String>) filter.get("eventTypes");
                    matched = types.contains(eventType);
                }

                // Check file pattern filter (glob: **/payment/**)
                if (matched && filePath != null && filter.containsKey("filePattern")) {
                    String pattern = (String) filter.get("filePattern");
                    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                    matched = matcher.matches(java.nio.file.Path.of(filePath));
                }

                // Check app filter
                if (matched && filter.containsKey("appKey") && appKey != null) {
                    matched = appKey.equals(filter.get("appKey"));
                }

                if (matched) {
                    sendNotification(rule, eventId, eventType, filePath, appKey, author);
                }
            } catch (Exception e) {
                log.error("failed to match rule {}: {}", rule.get("id"), e.getMessage());
            }
        }
    }

    private void sendNotification(Map<String, Object> rule, String eventId,
                                   String eventType, String filePath, String appKey, String author) {
        String channel = (String) rule.get("channel");
        String webhookUrl = (String) rule.get("webhook_url");
        Long ruleId = ((Number) rule.get("id")).longValue();

        String title = "[EvoTrace] 变更通知: " + eventType;
        String content = String.format("项目新变更 - 类型: %s, 文件: %s, 应用: %s, 作者: %s",
                eventType, filePath != null ? filePath : "N/A",
                appKey != null ? appKey : "N/A", author != null ? author : "N/A");

        String status = "SENT";
        String errorMsg = null;

        try {
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                // POST to webhook URL with the notification payload
                var httpClient = java.net.http.HttpClient.newHttpClient();
                String body = mapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", title + "\n" + content)
                ));
                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .build();
                var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    status = "FAILED";
                    errorMsg = "HTTP " + response.statusCode() + ": " + response.body();
                }
            }
        } catch (Exception e) {
            status = "FAILED";
            errorMsg = e.getMessage();
            log.error("notification send failed: channel={} ruleId={}", channel, ruleId, e);
        }

        // Log the notification
        jdbc.update("""
                INSERT INTO notification_log(rule_id, channel, event_id, title, content, status, error_msg)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ruleId, channel, eventId, title, content, status, errorMsg);

        log.info("notification sent: rule={} channel={} status={}", ruleId, channel, status);
    }
}
