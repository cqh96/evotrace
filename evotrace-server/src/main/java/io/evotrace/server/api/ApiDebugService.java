package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards debug requests to the target service base-url and returns the live
 * response; also serves mock responses from the endpoint's stored mock data.
 */
@Service
public class ApiDebugService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ApiRepository apiRepository;
    private final RestClient restClient;

    public ApiDebugService(JdbcTemplate jdbc, ApiRepository apiRepository) {
        this.jdbc = jdbc;
        this.apiRepository = apiRepository;
        this.restClient = RestClient.builder().build();
    }

    public record DebugRequest(Map<String, Object> request, String baseUrl, Map<String, String> headers) {}

    public record DebugResult(int status, Map<String, String> responseHeaders, Object body, long durationMs,
                              String error) {}

    /** Forward a debug request to the app's base-url (or an explicit base-url). */
    public DebugResult debug(Long projectId, Long appId, String method, String path,
                             Map<String, Object> request, String overrideBaseUrl) {
        Map<String, Object> req = request == null ? Map.of() : request;
        String baseUrl = overrideBaseUrl != null && !overrideBaseUrl.isBlank()
                ? overrideBaseUrl
                : appBaseUrl(appId);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("应用未配置 base-url，无法调试。请先在应用管理中配置调试目标地址。");
        }

        String finalPath = applyPathParams(path, req);
        String bodyStr = bodyJson(req);

        UriComponentsBuilder ub = UriComponentsBuilder.fromUriString(baseUrl).path(finalPath);
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) req.getOrDefault("query", Map.of());
        for (Map.Entry<String, Object> q : query.entrySet()) {
            if (q.getValue() != null) ub.queryParam(q.getKey(), q.getValue());
        }

        HttpHeaders headers = new HttpHeaders();
        @SuppressWarnings("unchecked")
        Map<String, Object> reqHeaders = (Map<String, Object>) req.getOrDefault("headers", Map.of());
        for (Map.Entry<String, Object> h : reqHeaders.entrySet()) {
            if (h.getValue() != null) headers.set(h.getKey(), String.valueOf(h.getValue()));
        }
        if (bodyStr != null && headers.get(HttpHeaders.CONTENT_TYPE) == null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        long start = System.currentTimeMillis();
        try {
            URI uri = ub.build().toUri();
            ResponseEntity<String> resp = restClient.method(HttpMethod.valueOf(method.toUpperCase()))
                    .uri(uri)
                    .headers(h -> h.addAll(headers))
                    .body(bodyStr)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, r) -> { /* 保留原始错误响应 */ })
                    .toEntity(String.class);
            long dur = System.currentTimeMillis() - start;
            return new DebugResult(resp.getStatusCode().value(), stringHeaders(resp.getHeaders()),
                    parseBody(resp.getBody()), dur, null);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            return new DebugResult(0, Map.of(), null, dur, e.getMessage());
        }
    }

    /** Return the stored mock response for an endpoint (generates one if absent). */
    public Map<String, Object> mock(Long projectId, Long endpointId) {
        ApiRepository.Endpoint ep = apiRepository.findById(endpointId);
        if (ep == null || !ep.projectId().equals(projectId)) {
            throw new IllegalArgumentException("接口不存在");
        }
        Map<String, Object> mock = ep.mockResponse();
        if (mock == null) {
            mock = ApiImporter.mockFromSchema(ep.responseSchema() == null ? Map.of() : ep.responseSchema());
            apiRepository.updateDetail(endpointId, null, null, null, null, null, mock);
        }
        return mock;
    }

    private String applyPathParams(String path, Map<String, Object> request) {
        String out = path;
        @SuppressWarnings("unchecked")
        Map<String, Object> pathParams = (Map<String, Object>) request.getOrDefault("pathParams", Map.of());
        for (Map.Entry<String, Object> p : pathParams.entrySet()) {
            if (p.getValue() != null) {
                out = out.replace("{" + p.getKey() + "}", String.valueOf(p.getValue()));
            }
        }
        return out;
    }

    private String bodyJson(Map<String, Object> request) {
        Object body = request.get("body");
        if (body == null) return null;
        if (body instanceof String s) return s;
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            return String.valueOf(body);
        }
    }

    private String appBaseUrl(Long appId) {
        if (appId == null) return null;
        try {
            return jdbc.queryForObject("SELECT base_url FROM application WHERE id = ?", String.class, appId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Map<String, String> stringHeaders(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, String.join(", ", v)));
        return out;
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readValue(body, Object.class);
        } catch (Exception e) {
            return body;
        }
    }
}