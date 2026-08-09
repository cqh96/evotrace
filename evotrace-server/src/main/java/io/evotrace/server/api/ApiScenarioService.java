package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.server.testplan.AssertionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口场景编排（对标 MeterSphere 场景自动化）。
 * <p>场景由有序步骤组成：HTTP / EXTRACT（变量提取）/ ASSERT（断言）/ IF（条件分支）。
 * 支持 ${var} 变量替换、环境（base-url + 全局变量 + headers）注入、逐步结果回流。</p>
 */
@Service
public class ApiScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ApiScenarioService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");
    private static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final JdbcTemplate jdbc;
    private final ApiRepository apiRepository;
    private final AssertionEvaluator assertionEvaluator;
    private final HttpClient httpClient;
    private final long stepTimeoutMs;

    public ApiScenarioService(JdbcTemplate jdbc, ApiRepository apiRepository,
                              AssertionEvaluator assertionEvaluator) {
        this.jdbc = jdbc;
        this.apiRepository = apiRepository;
        this.assertionEvaluator = assertionEvaluator;
        this.stepTimeoutMs = 8000;
        this.httpClient = buildHttpClient();
    }

    // ==================== 场景 CRUD ====================

    public List<Map<String, Object>> list(Long projectId) {
        return jdbc.queryForList("""
                SELECT id, name, description, enabled, created_by AS "createdBy", updated_at AS "updatedAt"
                FROM api_scenario WHERE project_id = ? ORDER BY updated_at DESC
                """, projectId);
    }

    public Map<String, Object> detail(Long projectId, Long scenarioId) {
        Map<String, Object> sc = jdbc.queryForMap(
                "SELECT id, project_id, name, description, enabled, created_by AS \"createdBy\" FROM api_scenario WHERE id = ? AND project_id = ?",
                scenarioId, projectId);
        sc.put("steps", jdbc.queryForList("""
                SELECT id, scenario_id AS "scenarioId", sort_order AS "sortOrder", step_type AS "stepType",
                       name, config_json AS "config"
                FROM api_scenario_step WHERE scenario_id = ? ORDER BY sort_order, id
                """, scenarioId));
        return sc;
    }

    @Transactional
    public Long create(Long projectId, String name, String description, List<Map<String, Object>> steps) {
        Long id = jdbc.queryForObject("""
                INSERT INTO api_scenario(project_id, name, description) VALUES (?, ?, ?) RETURNING id
                """, Long.class, projectId, name, description);
        saveSteps(id, steps);
        return id;
    }

    @Transactional
    public void update(Long projectId, Long scenarioId, String name, String description,
                       Boolean enabled, List<Map<String, Object>> steps) {
        jdbc.update("""
                UPDATE api_scenario SET name = COALESCE(?, name), description = COALESCE(?, description),
                    enabled = COALESCE(?, enabled), updated_at = now()
                WHERE id = ? AND project_id = ?
                """, name, description, enabled, scenarioId, projectId);
        if (steps != null) {
            jdbc.update("DELETE FROM api_scenario_step WHERE scenario_id = ?", scenarioId);
            saveSteps(scenarioId, steps);
        }
    }

    @Transactional
    public void delete(Long projectId, Long scenarioId) {
        jdbc.update("DELETE FROM api_scenario WHERE id = ? AND project_id = ?", scenarioId, projectId);
    }

    private void saveSteps(Long scenarioId, List<Map<String, Object>> steps) {
        if (steps == null) return;
        int order = 0;
        for (Map<String, Object> s : steps) {
            String type = String.valueOf(s.getOrDefault("stepType", s.getOrDefault("type", "HTTP"))).toUpperCase();
            String name = s.get("name") != null ? s.get("name").toString() : type + " " + (order + 1);
            Object config = s.get("config");
            if (config == null) config = s;
            try {
                jdbc.update("""
                        INSERT INTO api_scenario_step(scenario_id, sort_order, step_type, name, config_json)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        """, scenarioId, order++, type, name, mapper.writeValueAsString(config));
            } catch (Exception e) {
                throw new IllegalStateException("保存场景步骤失败: " + e.getMessage(), e);
            }
        }
    }

    // ==================== 场景执行 ====================

    /** 执行场景：注入环境（可选）→ 逐步执行 → 返回逐步结果。 */
    public Map<String, Object> run(Long projectId, Long scenarioId, Long environmentId, Map<String, Object> overrides) {
        Map<String, Object> sc = detail(projectId, scenarioId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) sc.get("steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("场景无步骤");
        }

        // 环境上下文
        Map<String, Object> ctx = new LinkedHashMap<>();
        ApiRepository.Environment env = environmentId != null ? apiRepository.findEnvironment(environmentId) : null;
        if (env != null) {
            if (env.baseUrl() != null) ctx.put("baseUrl", env.baseUrl());
            if (env.variables() != null) ctx.putAll(env.variables());
        }
        if (overrides != null) ctx.putAll(overrides);

        List<Map<String, Object>> stepResults = new ArrayList<>();
        String verdict = "PASSED";
        long started = System.nanoTime();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            if (!"PASSED".equals(verdict)) {
                stepResults.add(skipped(step, i, "上一步失败，跳过"));
                continue;
            }
            Map<String, Object> r;
            try {
                r = executeStep(step, i, ctx);
            } catch (Exception e) {
                log.warn("scenario step error: index={} error={}", i, e.getMessage());
                r = failed(step, i, e.getMessage());
                verdict = "FAILED";
                stepResults.add(r);
                continue;
            }
            if (!"PASSED".equals(r.get("status"))) {
                verdict = "FAILED";
            }
            stepResults.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioId", scenarioId);
        result.put("scenarioName", sc.get("name"));
        result.put("verdict", verdict);
        result.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        result.put("steps", stepResults);
        result.put("variables", ctx);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeStep(Map<String, Object> step, int index, Map<String, Object> ctx) throws Exception {
        String type = String.valueOf(step.getOrDefault("stepType", "HTTP")).toUpperCase();
        Map<String, Object> cfg = step.get("config") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();
        // 兼容直接把请求字段放在 step 上的写法
        if (cfg.isEmpty() && step.get("method") != null) cfg = new LinkedHashMap<>(step);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("name", step.get("name"));
        r.put("type", type);
        switch (type) {
            case "HTTP" -> r.putAll(executeHttp(cfg, ctx));
            case "SQL" -> r.putAll(executeSql(cfg, ctx));
            case "TCP" -> r.putAll(executeTcp(cfg, ctx));
            case "EXTRACT" -> r.putAll(executeExtract(cfg, ctx));
            case "ASSERT" -> r.putAll(executeAssert(cfg, ctx));
            case "IF" -> r.putAll(executeIf(cfg, ctx));
            default -> {
                r.put("status", "FAILED");
                r.put("error", "未知步骤类型: " + type);
            }
        }
        return r;
    }

    private Map<String, Object> executeHttp(Map<String, Object> cfg, Map<String, Object> ctx) throws Exception {
        String method = String.valueOf(cfg.getOrDefault("method", "GET")).toUpperCase();
        if (!HTTP_METHODS.contains(method)) {
            throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        }
        String url = resolveUrl(cfg, ctx);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HTTP 步骤缺少 url（可用 ${baseUrl} + 路径）");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("仅支持 http/https: " + url);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(stepTimeoutMs));
        Map<String, Object> headers = (Map<String, Object>) cfg.getOrDefault("headers", Map.of());
        for (Map.Entry<String, Object> e : headers.entrySet()) {
            if (e.getValue() != null) rb.header(e.getKey(), substitute(String.valueOf(e.getValue()), ctx));
        }
        String body = cfg.get("body") != null ? substitute(String.valueOf(cfg.get("body")), ctx) : "";
        boolean hasBody = !body.isBlank();
        boolean hasContentType = headers.keySet().stream().anyMatch(k -> "content-type".equalsIgnoreCase(String.valueOf(k)));
        if (hasBody && !hasContentType) rb.header("Content-Type", "application/json");
        rb.method(method, hasBody ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

        long start = System.nanoTime();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        } catch (Exception e) {
            throw new IllegalStateException("请求失败: " + e.getMessage());
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String respBody = resp.body() != null ? resp.body() : "";

        List<Map<String, Object>> assertions = (List<Map<String, Object>>) cfg.getOrDefault("assertions", List.of());
        List<Map<String, Object>> assertionResults = assertionEvaluator.evaluate(assertions, resp.statusCode(), respBody, durationMs);
        boolean allPassed = assertionEvaluator.allPassed(assertionResults);
        boolean statusOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
        boolean passed = assertionResults.isEmpty() ? statusOk : allPassed;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("method", method);
        r.put("url", url);
        r.put("statusCode", resp.statusCode());
        r.put("durationMs", durationMs);
        r.put("responseSnippet", truncate(respBody));
        r.put("assertions", assertionResults);
        r.put("status", passed ? "PASSED" : "FAILED");
        if (!passed) {
            List<String> reasons = new ArrayList<>();
            for (Map<String, Object> a : assertionResults) {
                if (!Boolean.TRUE.equals(a.get("passed"))) reasons.add(String.valueOf(a.get("message")));
            }
            if (reasons.isEmpty() && !statusOk) reasons.add("状态码 " + resp.statusCode() + " 非 2xx");
            r.put("error", String.join("；", reasons));
        }
        // 注入最后响应供 EXTRACT/ASSERT 使用
        ctx.put("__lastResponse", respBody);
        ctx.put("__lastStatus", resp.statusCode());
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSql(Map<String, Object> cfg, Map<String, Object> ctx) {
        String jdbcUrl = substitute(String.valueOf(cfg.getOrDefault("jdbcUrl", "")), ctx);
        String user = substitute(String.valueOf(cfg.getOrDefault("user", "")), ctx);
        String password = substitute(String.valueOf(cfg.getOrDefault("password", "")), ctx);
        String sql = substitute(String.valueOf(cfg.getOrDefault("sql", "")), ctx);
        if (jdbcUrl.isBlank() || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 步骤缺少 jdbcUrl 或 sql");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jdbcUrl", jdbcUrl);
        r.put("sql", sql);
        long start = System.nanoTime();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, user, password);
             java.sql.Statement st = conn.createStatement()) {
            boolean isQuery = st.execute(sql);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            r.put("durationMs", durationMs);
            if (isQuery) {
                try (java.sql.ResultSet rs = st.getResultSet()) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    int limit = 0;
                    while (rs.next() && limit++ < 100) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                    r.put("rows", rows);
                    r.put("rowCount", rows.size());
                }
            } else {
                r.put("updateCount", st.getUpdateCount());
            }
            r.put("status", "PASSED");
        } catch (Exception e) {
            r.put("status", "FAILED");
            r.put("error", "SQL 执行失败: " + e.getMessage());
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeTcp(Map<String, Object> cfg, Map<String, Object> ctx) {
        String host = substitute(String.valueOf(cfg.getOrDefault("host", "")), ctx);
        int port = cfg.get("port") instanceof Number n ? n.intValue() : 0;
        String payload = substitute(String.valueOf(cfg.getOrDefault("payload", "")), ctx);
        if (host.isBlank() || port <= 0) {
            throw new IllegalArgumentException("TCP 步骤缺少 host 或 port");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("host", host);
        r.put("port", port);
        r.put("payload", payload);
        long start = System.nanoTime();
        try (java.net.Socket socket = new java.net.Socket(host, port);
             java.io.OutputStream out = socket.getOutputStream();
             java.io.InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(8000);
            byte[] send = payload.getBytes(StandardCharsets.UTF_8);
            out.write(send);
            out.flush();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int nRead;
            while ((nRead = in.read(buf)) != -1) {
                bos.write(buf, 0, nRead);
                if (bos.size() > 8192) break;
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String resp = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            r.put("durationMs", durationMs);
            r.put("response", truncate(resp));
            r.put("status", "PASSED");
            ctx.put("__lastResponse", resp);
        } catch (Exception e) {
            r.put("status", "FAILED");
            r.put("error", "TCP 通信失败: " + e.getMessage());
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeExtract(Map<String, Object> cfg, Map<String, Object> ctx) {
        String variable = String.valueOf(cfg.get("variable"));
        String path = String.valueOf(cfg.getOrDefault("path", ""));
        Object found = null;
        String lastBody = (String) ctx.get("__lastResponse");
        if (lastBody != null) {
            try {
                Object parsed = mapper.readValue(lastBody, Object.class);
                found = resolvePath(parsed, path);
            } catch (Exception e) {
                found = null;
            }
        }
        if (variable != null && !variable.isBlank() && found != null) {
            ctx.put(variable, String.valueOf(found));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("variable", variable);
        r.put("path", path);
        r.put("value", found != null ? String.valueOf(found) : null);
        r.put("status", found != null ? "PASSED" : "FAILED");
        if (found == null) r.put("error", "未提取到变量: " + variable + " (path=" + path + ")");
        return r;
    }

    private Map<String, Object> executeAssert(Map<String, Object> cfg, Map<String, Object> ctx) {
        String lastBody = (String) ctx.get("__lastResponse");
        int lastStatus = ctx.get("__lastStatus") instanceof Number n ? n.intValue() : 0;
        List<Map<String, Object>> assertions = List.of(cfg);
        List<Map<String, Object>> results = assertionEvaluator.evaluate(assertions, lastStatus,
                lastBody == null ? "" : lastBody, 0);
        boolean passed = assertionEvaluator.allPassed(results);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("assertions", results);
        r.put("status", passed ? "PASSED" : "FAILED");
        if (!passed) {
            r.put("error", results.stream()
                    .filter(a -> !Boolean.TRUE.equals(a.get("passed")))
                    .map(a -> String.valueOf(a.get("message"))).toList());
        }
        return r;
    }

    private Map<String, Object> executeIf(Map<String, Object> cfg, Map<String, Object> ctx) {
        String condition = substitute(String.valueOf(cfg.getOrDefault("condition", "")), ctx);
        boolean ok = evaluateCondition(condition, ctx);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("condition", condition);
        r.put("result", ok);
        r.put("status", "PASSED"); // 条件步骤本身不判失败，仅记录分支
        r.put("note", ok ? "条件成立，继续执行" : "条件不成立，跳过后续（fail-fast）");
        return r;
    }

    /** 简单条件求值：支持 "var == value" / "status == 200" / 布尔值。 */
    private boolean evaluateCondition(String condition, Map<String, Object> ctx) {
        String c = condition.trim();
        if (c.equalsIgnoreCase("true")) return true;
        if (c.equalsIgnoreCase("false")) return false;
        Matcher m = Pattern.compile("^(\\w+)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(c);
        if (m.matches()) {
            String lhs = ctx.get(m.group(1)) != null ? String.valueOf(ctx.get(m.group(1))) : m.group(1);
            String rhs = m.group(3).trim().replaceAll("^['\"](.*)['\"]$", "$1");
            String op = m.group(2);
            try {
                double a = Double.parseDouble(lhs);
                double b = Double.parseDouble(rhs);
                return switch (op) {
                    case "==" -> a == b;
                    case "!=" -> a != b;
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    case ">" -> a > b;
                    case "<" -> a < b;
                    default -> false;
                };
            } catch (NumberFormatException e) {
                return switch (op) {
                    case "==" -> lhs.equals(rhs);
                    case "!=" -> !lhs.equals(rhs);
                    default -> false;
                };
            }
        }
        return false;
    }

    private String resolveUrl(Map<String, Object> cfg, Map<String, Object> ctx) {
        String url = cfg.get("url") != null ? String.valueOf(cfg.get("url")) : "";
        return substitute(url, ctx);
    }

    /** ${var} 替换。 */
    private String substitute(String text, Map<String, Object> ctx) {
        if (text == null) return null;
        Matcher m = VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = ctx.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? String.valueOf(v) : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 按点号路径从 JSON 中取值（支持数组下标，如 data.items[0].id）。 */
    @SuppressWarnings("unchecked")
    private Object resolvePath(Object node, String path) {
        if (path == null || path.isBlank()) return node;
        Object cur = node;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            Matcher m = Pattern.compile("^(\\w+)(\\[(\\d+)])?$").matcher(seg);
            if (!m.matches()) return null;
            String key = m.group(1);
            if (cur instanceof Map<?, ?> map) {
                cur = map.get(key);
            } else {
                return null;
            }
            if (m.group(3) != null) {
                int idx = Integer.parseInt(m.group(3));
                if (!(cur instanceof List<?> list) || idx >= list.size()) return null;
                cur = list.get(idx);
            }
        }
        return cur;
    }

    private Map<String, Object> skipped(Map<String, Object> step, int index, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("name", step.get("name"));
        r.put("type", step.getOrDefault("stepType", "HTTP"));
        r.put("status", "SKIPPED");
        r.put("error", reason);
        return r;
    }

    private Map<String, Object> failed(Map<String, Object> step, int index, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("name", step.get("name"));
        r.put("type", step.getOrDefault("stepType", "HTTP"));
        r.put("status", "FAILED");
        r.put("error", error);
        return r;
    }

    private String truncate(String body) {
        if (body == null) return "";
        return body.length() <= 2000 ? body : body.substring(0, 2000) + "…(已截断)";
    }

    private static HttpClient buildHttpClient() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).sslContext(ctx).build();
        } catch (Exception e) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }
    }
}