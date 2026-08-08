package io.evotrace.idea.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.evotrace.idea.settings.EvotraceSettings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class EvotraceClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final AtomicReference<String> TOKEN = new AtomicReference<>();

    private EvotraceClient() {
    }

    public static void clearToken() {
        TOKEN.set(null);
    }

    public static List<FileHistoryEntry> fetchFileHistory(String relativePath) throws Exception {
        EvotraceSettings s = EvotraceSettings.getInstance();
        String url = s.normalizedServerUrl() + "/api/v1/files/history?path="
                + encode(relativePath) + "&projectKey=" + encode(s.effectiveProjectKey());
        JsonObject root = getJsonAuthed(url);
        if (!root.has("success") || !root.get("success").getAsBoolean()) {
            throw new IllegalStateException(messageOf(root, "查询文件历史失败"));
        }
        List<FileHistoryEntry> list = new ArrayList<>();
        JsonArray data = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data") : new JsonArray();
        for (JsonElement el : data) {
            JsonObject o = el.getAsJsonObject();
            list.add(new FileHistoryEntry(
                    text(o, "eventId"),
                    text(o, "eventType"),
                    text(o, "commitSha"),
                    text(o, "author"),
                    text(o, "commitMessage"),
                    text(o, "occurredAt"),
                    text(o, "changeKind"),
                    intVal(o, "addLines"),
                    intVal(o, "delLines"),
                    text(o, "summary"),
                    text(o, "diff"),
                    o.has("hasDiff") && o.get("hasDiff").getAsBoolean()
            ));
        }
        return list;
    }

    public static DashboardData fetchDashboard() throws Exception {
        EvotraceSettings s = EvotraceSettings.getInstance();
        JsonObject statsRoot = getJsonAuthed(s.normalizedServerUrl() + "/api/v1/dashboard/stats");
        JsonObject hotspotsRoot = getJsonAuthed(s.normalizedServerUrl()
                + "/api/v1/projects/" + encode(s.effectiveProjectKey())
                + "/analysis/hotspots?days=30");

        DashboardData data = new DashboardData();
        data.projectKey = s.effectiveProjectKey();
        if (statsRoot.has("success") && statsRoot.get("success").getAsBoolean()
                && statsRoot.has("data") && statsRoot.get("data").isJsonObject()) {
            JsonObject d = statsRoot.getAsJsonObject("data");
            data.projectCount = intVal(d, "projectCount");
            data.appCount = intVal(d, "appCount");
            data.todayChanges = intVal(d, "todayChanges");
            data.releaseCount = intVal(d, "releaseCount");
        }
        if (hotspotsRoot.has("success") && hotspotsRoot.get("success").getAsBoolean()
                && hotspotsRoot.has("data") && hotspotsRoot.get("data").isJsonObject()) {
            JsonObject d = hotspotsRoot.getAsJsonObject("data");
            if (d.has("topChangedFiles") && d.get("topChangedFiles").isJsonArray()) {
                for (JsonElement el : d.getAsJsonArray("topChangedFiles")) {
                    JsonObject f = el.getAsJsonObject();
                    data.hotFiles.add(new HotFile(text(f, "filePath"), intVal(f, "changeCount")));
                    if (data.hotFiles.size() >= 10) break;
                }
            }
        }
        return data;
    }

    private static JsonObject getJsonAuthed(String url) throws Exception {
        ensureToken();
        HttpResponse<String> resp = sendGet(url, TOKEN.get());
        if (resp.statusCode() == 401) {
            clearToken();
            ensureToken();
            resp = sendGet(url, TOKEN.get());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("请求失败，状态代码为 " + resp.statusCode()
                    + (resp.body() != null && !resp.body().isBlank() ? ": " + truncate(resp.body(), 200) : ""));
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    private static synchronized void ensureToken() throws Exception {
        if (TOKEN.get() != null && !TOKEN.get().isBlank()) {
            return;
        }
        EvotraceSettings s = EvotraceSettings.getInstance();
        String body = "{\"username\":\"" + escapeJson(s.effectiveUsername())
                + "\",\"password\":\"" + escapeJson(s.effectivePassword()) + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(s.normalizedServerUrl() + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("登录失败 (" + resp.statusCode() + ")，请在 Settings → Tools → EvoTrace 检查账号密码");
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()
                || !root.has("data") || !root.get("data").isJsonObject()) {
            throw new IllegalStateException(messageOf(root, "登录失败，请检查用户名/密码"));
        }
        String token = text(root.getAsJsonObject("data"), "token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("登录响应缺少 token");
        }
        TOKEN.set(token);
    }

    private static HttpResponse<String> sendGet(String url, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static int intVal(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String messageOf(JsonObject root, String fallback) {
        if (root.has("message") && !root.get("message").isJsonNull()) {
            return root.get("message").getAsString();
        }
        return fallback;
    }

    public record FileHistoryEntry(
            String eventId,
            String eventType,
            String commitSha,
            String author,
            String commitMessage,
            String occurredAt,
            String changeKind,
            int addLines,
            int delLines,
            String summary,
            String diff,
            boolean hasDiff
    ) {
    }

    public static final class DashboardData {
        public String projectKey;
        public int projectCount;
        public int appCount;
        public int todayChanges;
        public int releaseCount;
        public final List<HotFile> hotFiles = new ArrayList<>();
    }

    public record HotFile(String filePath, int changeCount) {
    }
}
