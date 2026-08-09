package io.evotrace.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes API definitions from multiple sources into endpoint drafts:
 * OpenAPI/Swagger (JSON/YAML), Postman Collection v2, cURL, Apifox.
 * YAML is parsed via Jackson's YAML factory (spring-boot-starter-web brings SnakeYAML).
 */
@Component
public class ApiImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();

    public record Draft(String method, String path, String name, String summary, List<String> tags,
                        List<Map<String, Object>> params, Map<String, Object> requestBody,
                        Map<String, Object> responseSchema, Map<String, Object> mockResponse) {}

    /** Entry point: detect format and parse. */
    public List<Draft> importFile(String format, String content) {
        return switch (format == null ? "" : format.toLowerCase()) {
            case "openapi", "swagger", "yaml", "yml" -> fromOpenApi(content);
            case "postman" -> fromPostman(content);
            case "curl" -> List.of(fromCurl(content));
            case "apifox" -> fromApifox(content);
            default -> throw new IllegalArgumentException("不支持的数据源格式: " + format);
        };
    }

    /* ---------------- OpenAPI / Swagger ---------------- */

    public List<Draft> fromOpenApi(String content) {
        try {
            JsonNode root = isJson(content) ? MAPPER.readTree(content) : YAML.readTree(content);
            JsonNode paths = root.path("paths");
            List<Draft> drafts = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
            while (pathIt.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathIt.next();
                String path = pathEntry.getKey();
                JsonNode ops = pathEntry.getValue();
                Iterator<Map.Entry<String, JsonNode>> opIt = ops.fields();
                while (opIt.hasNext()) {
                    Map.Entry<String, JsonNode> op = opIt.next();
                    String method = op.getKey().toUpperCase();
                    if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) continue;
                    JsonNode opNode = op.getValue();
                    drafts.add(buildDraft(method, path, opNode));
                }
            }
            return drafts;
        } catch (Exception e) {
            throw new IllegalArgumentException("OpenAPI 解析失败: " + e.getMessage(), e);
        }
    }

    private Draft buildDraft(String method, String path, JsonNode opNode) {
        List<String> tags = jsonArrayOfStrings(opNode.path("tags"));
        String summary = opNode.get("summary") != null ? opNode.get("summary").asText() : null;
        String name = opNode.get("operationId") != null ? opNode.get("operationId").asText() : summary;

        List<Map<String, Object>> params = new ArrayList<>();
        for (JsonNode p : opNode.path("parameters")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.path("name").asText());
            m.put("in", p.path("in").asText());
            m.put("required", p.path("required").asBoolean(false));
            m.put("type", schemaType(p.path("schema")));
            m.put("desc", p.path("description").asText(""));
            params.add(m);
        }

        Map<String, Object> requestBody = null;
        JsonNode rb = opNode.path("requestBody");
        if (!rb.isMissingNode()) {
            requestBody = extractSchema(rb.path("content"));
        }

        Map<String, Object> responseSchema = null;
        JsonNode responses = opNode.path("responses");
        while (responses != null && responses.isArray() && !responses.isEmpty()) {
            responses = responses.get(0);
        }
        if (!responses.isMissingNode()) {
            JsonNode ok = responses.path("200");
            if (ok.isMissingNode()) ok = responses.path("default");
            if (!ok.isMissingNode()) {
                responseSchema = extractSchema(ok.path("content"));
            }
        }

        // deterministic mock from response schema
        Map<String, Object> mock = responseSchema == null ? null : mockFromSchema(responseSchema);
        return new Draft(method, path, name, summary, tags, params, requestBody, responseSchema, mock);
    }

    private Map<String, Object> extractSchema(JsonNode content) {
        if (content.isMissingNode() || !content.isObject() || content.isEmpty()) return null;
        JsonNode any = content.fields().hasNext() ? content.elements().next() : null;
        if (any == null) return null;
        JsonNode schema = any.path("schema");
        if (schema.isMissingNode()) return null;
        return MAPPER.convertValue(schema, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private String schemaType(JsonNode schema) {
        JsonNode t = schema.path("type");
        if (!t.isMissingNode()) return t.asText();
        JsonNode ref = schema.path("$ref");
        if (!ref.isMissingNode()) return ref.asText();
        return "";
    }

    /* ---------------- Postman Collection v2 ---------------- */

    public List<Draft> fromPostman(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            List<Draft> drafts = new ArrayList<>();
            JsonNode items = root.path("item");
            if (items.isArray()) {
                for (JsonNode item : items) walkPostman(item, drafts);
            }
            return drafts;
        } catch (Exception e) {
            throw new IllegalArgumentException("Postman 解析失败: " + e.getMessage(), e);
        }
    }

    private void walkPostman(JsonNode node, List<Draft> drafts) {
        JsonNode request = node.path("request");
        if (!request.isMissingNode()) {
            String method = request.path("method").asText("GET").toUpperCase();
            String path = request.path("url").path("raw").asText("");
            if (path.isEmpty()) {
                JsonNode url = request.path("url");
                StringBuilder sb = new StringBuilder();
                for (JsonNode seg : url.path("path")) sb.append('/').append(seg.asText());
                path = sb.length() == 0 ? "/" : sb.toString();
            }
            List<Map<String, Object>> params = new ArrayList<>();
            for (JsonNode q : request.path("url").path("query")) {
                params.add(Map.of("name", q.path("key").asText(), "in", "query",
                        "required", false, "type", "string", "desc", q.path("description").asText("")));
            }
            Map<String, Object> requestBody = null;
            JsonNode body = request.path("body");
            if (!body.isMissingNode() && body.path("raw").asText("").isBlank() == false) {
                requestBody = Map.of("raw", body.path("raw").asText());
            }
            drafts.add(new Draft(method, path, node.path("name").asText(), null, List.of(), params, requestBody, null, null));
        } else {
            JsonNode children = node.path("item");
            if (children.isArray()) {
                for (JsonNode c : children) walkPostman(c, drafts);
            }
        }
    }

    /* ---------------- cURL ---------------- */

    public Draft fromCurl(String content) {
        String cmd = content.replace("\\\n", " ").trim();
        String[] tokens = cmd.split("\\s+");
        String method = "GET";
        String url = null;
        Map<String, String> headers = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            switch (t) {
                case "-X", "--request" -> { if (i + 1 < tokens.length) method = tokens[++i].toUpperCase(); }
                case "-H", "--header" -> {
                    if (i + 1 < tokens.length) {
                        String h = tokens[++i];
                        int c = h.indexOf(':');
                        if (c > 0) headers.put(h.substring(0, c).trim(), h.substring(c + 1).trim());
                    }
                }
                case "-d", "--data", "--data-raw", "--data-binary" -> {
                    method = "POST";
                    if (i + 1 < tokens.length) { body.append(tokens[++i]); }
                }
                default -> {
                    if (url == null && (t.startsWith("http://") || t.startsWith("https://"))) url = t;
                }
            }
        }
        if (url == null) throw new IllegalArgumentException("cURL 中未找到有效 URL");
        List<Map<String, Object>> params = new ArrayList<>();
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (url.contains("?")) {
            for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
                String k = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                params.add(Map.of("name", k, "in", "query", "required", false, "type", "string", "desc", ""));
            }
        }
        Map<String, Object> requestBody = body.length() > 0 ? Map.of("raw", body.toString()) : null;
        return new Draft(method, path, null, null, List.of(), params, requestBody, null, null);
    }

    /* ---------------- Apifox ---------------- */

    public List<Draft> fromApifox(String content) {
        try {
            JsonNode root = isJson(content) ? MAPPER.readTree(content) : YAML.readTree(content);
            List<Draft> drafts = new ArrayList<>();
            JsonNode items = root.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) walkApifox(item, drafts);
            }
            return drafts;
        } catch (Exception e) {
            throw new IllegalArgumentException("Apifox 解析失败: " + e.getMessage(), e);
        }
    }

    private void walkApifox(JsonNode node, List<Draft> drafts) {
        if (node.path("method").asText("").isBlank() == false) {
            String method = node.path("method").asText().toUpperCase();
            String path = node.path("path").asText("");
            String name = node.path("name").asText();
            List<Map<String, Object>> params = new ArrayList<>();
            for (JsonNode p : node.path("parameters")) {
                params.add(Map.of("name", p.path("name").asText(), "in", p.path("type").asText(",type"),
                        "required", p.path("required").asBoolean(false), "type", "string",
                        "desc", p.path("description").asText("")));
            }
            drafts.add(new Draft(method, path, name, null, List.of(), params, null, null, null));
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode c : children) walkApifox(c, drafts);
        }
    }

    /* ---------------- helpers ---------------- */

    private List<String> jsonArrayOfStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) for (JsonNode n : node) out.add(n.asText());
        return out;
    }

    private boolean isJson(String content) {
        String trimmed = content == null ? "" : content.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /** Generate a deterministic example payload from a minimal JSON schema. */
    static Map<String, Object> mockFromSchema(Map<String, Object> schema) {
        Object example = schema.get("example");
        if (example != null) {
            if (example instanceof Map<?, ?> orm) return new LinkedHashMap<>(toStrMap(orm));
            return Map.of("value", example);
        }
        String type = String.valueOf(schema.getOrDefault("type", "object"));
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case "array" -> {
                Object items = schema.get("items");
                Map<String, Object> itemSchema = items instanceof Map<?, ?> im ? toStrMap(im) : Map.of();
                out.put("items", List.of(sampleScalarOf(itemSchema)));
            }
            case "object" -> {
                Object props = schema.get("properties");
                if (props instanceof Map<?, ?> pm) {
                    for (Map.Entry<?, ?> e : pm.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof Map<?, ?> vm) {
                            out.put(String.valueOf(e.getKey()), sampleScalarOf(toStrMap(vm)));
                        }
                    }
                }
            }
            default -> out.put("value", sampleScalar(type));
        }
        return out;
    }

    private static Map<String, Object> toStrMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static Object sampleScalarOf(Map<String, Object> schema) {
        return sampleScalar(String.valueOf(schema.getOrDefault("type", "string")));
    }

    private static Object sampleScalar(String type) {
        return switch (type) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            case "array" -> List.of();
            case "object" -> Map.of();
            default -> "string";
        };
    }
}