package io.evotrace.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置 Mock 服务：把对 {@code /api/v1/mock/{projectKey}/{appKey}/**} 的请求，
 * 匹配到该应用已登记的接口（支持 {param} 路径变量），直接返回该接口保存的 mock 响应。
 */
@RestController
public class ApiMockController {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ApiMockController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @RequestMapping(value = {"/api/v1/mock/{projectKey}/{path:[\\s\\S]*}"}, method = {
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.DELETE,
            org.springframework.web.bind.annotation.RequestMethod.PATCH})
    public ResponseEntity<?> mock(HttpServletRequest request,
                                  @PathVariable String projectKey,
                                  @PathVariable(required = false) String path) {
        try {
            String method = request.getMethod();
            String requestPath = "/" + (path == null ? "" : path);
            Long projectId = jdbc.queryForObject("SELECT id FROM project WHERE project_key = ?", Long.class, projectKey);

            List<ApiRepository.Endpoint> candidates = jdbc.query("""
                    SELECT e.id, e.project_id, e.app_id, a.app_key, e.method, e.path, e.name, e.summary,
                           e.tags_json, e.params_json, e.request_body_json, e.response_schema_json,
                           e.mock_response_json, e.source
                    FROM api_endpoint e
                    LEFT JOIN application a ON a.id = e.app_id
                    WHERE e.project_id = ?
                    """, rowMapper(), projectId);

            ApiRepository.Endpoint matched = null;
            for (ApiRepository.Endpoint ep : candidates) {
                if (!ep.method().equalsIgnoreCase(method)) continue;
                if (matches(ep.path(), requestPath)) { matched = ep; break; }
            }
            if (matched == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> mock = matched.mockResponse();
            if (mock == null) {
                mock = ApiImporter.mockFromSchema(matched.responseSchema() == null ? Map.of() : matched.responseSchema());
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(mock);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Result.fail("EVO-SYS-500", "Mock 服务异常: " + e.getMessage()));
        }
    }

    private boolean matches(String endpointPath, String requestPath) {
        String regex = endpointPath.replaceAll("\\{[^}]+\\}", "[^/]+");
        return Pattern.matches(regex, requestPath);
    }

    private org.springframework.jdbc.core.RowMapper<ApiRepository.Endpoint> rowMapper() {
        return (rs, i) -> {
            try {
                List<String> tags = rs.getString("tags_json") == null ? List.of()
                        : mapper.readValue(rs.getString("tags_json"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                List<Map<String, Object>> params = rs.getString("params_json") == null ? List.of()
                        : mapper.readValue(rs.getString("params_json"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Map<String, Object> req = rs.getString("request_body_json") == null ? null
                        : mapper.readValue(rs.getString("request_body_json"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Map<String, Object> res = rs.getString("response_schema_json") == null ? null
                        : mapper.readValue(rs.getString("response_schema_json"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                Map<String, Object> mock = rs.getString("mock_response_json") == null ? null
                        : mapper.readValue(rs.getString("mock_response_json"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                long appId = rs.getLong("app_id");
                return new ApiRepository.Endpoint(rs.getLong("id"), rs.getLong("project_id"),
                        rs.wasNull() ? null : appId, rs.getString("app_key"), rs.getString("method"),
                        rs.getString("path"), rs.getString("name"), rs.getString("summary"), tags, params,
                        req, res, mock, rs.getString("source"));
            } catch (Exception e) {
                throw new java.sql.SQLException(e);
            }
        };
    }
}