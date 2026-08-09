package io.evotrace.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Data access for the API debug workspace (endpoints, environments, test cases).
 * JSON columns are stored as JSONB and read back as typed structures.
 */
@Repository
public class ApiRepository {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ApiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------- endpoint ---------------- */

    public record Endpoint(Long id, Long projectId, Long appId, String appKey, String method, String path,
                           String name, String summary, List<String> tags, List<Map<String, Object>> params,
                           Map<String, Object> requestBody, Map<String, Object> responseSchema,
                           Map<String, Object> mockResponse, String source) {}

    private static final RowMapper<Endpoint> ENDPOINT_ROW = (rs, i) -> {
        try {
            List<String> tags = rs.getString("tags_json") == null ? List.of()
                    : mapper.readValue(rs.getString("tags_json"), new TypeReference<>() {});
            List<Map<String, Object>> params = rs.getString("params_json") == null ? List.of()
                    : mapper.readValue(rs.getString("params_json"), new TypeReference<>() {});
            Map<String, Object> req = readMap(rs, "request_body_json");
            Map<String, Object> res = readMap(rs, "response_schema_json");
            Map<String, Object> mock = readMap(rs, "mock_response_json");
            return new Endpoint(
                    rs.getLong("id"), rs.getLong("project_id"), getLong(rs, "app_id"),
                    rs.getString("app_key"), rs.getString("method"), rs.getString("path"),
                    rs.getString("name"), rs.getString("summary"), tags, params, req, res, mock,
                    rs.getString("source"));
        } catch (Exception e) {
            throw new SQLException(e);
        }
    };

    private static Map<String, Object> readMap(ResultSet rs, String col) throws Exception {
        if (rs.getString(col) == null) return null;
        return mapper.readValue(rs.getString(col), new TypeReference<>() {});
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public List<Endpoint> listByProject(Long projectId) {
        return jdbc.query("""
                SELECT e.id, e.project_id, e.app_id, a.app_key, e.method, e.path, e.name, e.summary,
                       e.tags_json, e.params_json, e.request_body_json, e.response_schema_json,
                       e.mock_response_json, e.source
                FROM api_endpoint e
                LEFT JOIN application a ON a.id = e.app_id
                WHERE e.project_id = ?
                ORDER BY e.app_id NULLS LAST, e.path
                """, ENDPOINT_ROW, projectId);
    }

    public Endpoint findById(Long id) {
        List<Endpoint> rows = jdbc.query("""
                SELECT e.id, e.project_id, e.app_id, a.app_key, e.method, e.path, e.name, e.summary,
                       e.tags_json, e.params_json, e.request_body_json, e.response_schema_json,
                       e.mock_response_json, e.source
                FROM api_endpoint e
                LEFT JOIN application a ON a.id = e.app_id
                WHERE e.id = ?
                """, ENDPOINT_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(Long projectId, Long appId, String method, String path, String name, String summary,
                       List<String> tags, List<Map<String, Object>> params, Map<String, Object> requestBody,
                       Map<String, Object> responseSchema, Map<String, Object> mockResponse, String source) {
        try {
            jdbc.update("""
                    INSERT INTO api_endpoint(project_id, app_id, method, path, name, summary, tags_json,
                        params_json, request_body_json, response_schema_json, mock_response_json, source, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, now())
                    ON CONFLICT (project_id, app_id, method, path)
                    DO UPDATE SET name = EXCLUDED.name, summary = EXCLUDED.summary,
                        tags_json = EXCLUDED.tags_json, params_json = EXCLUDED.params_json,
                        request_body_json = EXCLUDED.request_body_json,
                        response_schema_json = EXCLUDED.response_schema_json,
                        source = CASE WHEN api_endpoint.source = 'INVENTORY' AND EXCLUDED.source <> 'INVENTORY'
                                      THEN EXCLUDED.source ELSE api_endpoint.source END,
                        updated_at = now()
                    """, projectId, appId, method, path, name, summary,
                    mapper.writeValueAsString(tags == null ? List.of() : tags),
                    mapper.writeValueAsString(params == null ? List.of() : params),
                    jsonOrNull(requestBody), jsonOrNull(responseSchema), jsonOrNull(mockResponse), source);
        } catch (Exception e) {
            throw new IllegalStateException("保存接口失败: " + e.getMessage(), e);
        }
    }

    public void updateDetail(Long id, String name, String summary, List<Map<String, Object>> params,
                             Map<String, Object> requestBody, Map<String, Object> responseSchema,
                             Map<String, Object> mockResponse) {
        try {
            jdbc.update("""
                    UPDATE api_endpoint
                    SET name = COALESCE(?, name), summary = COALESCE(?, summary),
                        params_json = COALESCE(?::jsonb, params_json),
                        request_body_json = COALESCE(?::jsonb, request_body_json),
                        response_schema_json = COALESCE(?::jsonb, response_schema_json),
                        mock_response_json = COALESCE(?::jsonb, mock_response_json),
                        updated_at = now()
                    WHERE id = ?
                    """, name, summary, jsonOrNull(params), jsonOrNull(requestBody),
                    jsonOrNull(responseSchema), jsonOrNull(mockResponse), id);
        } catch (Exception e) {
            throw new IllegalStateException("更新接口失败: " + e.getMessage(), e);
        }
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM api_endpoint WHERE id = ?", id);
    }

    /* ---------------- environment ---------------- */

    public record Environment(Long id, Long projectId, String name, String baseUrl,
                              Map<String, String> headers, Map<String, Object> variables) {}

    private static final RowMapper<Environment> ENV_ROW = (rs, i) -> {
        try {
            Map<String, String> headers = rs.getString("headers_json") == null ? Map.of()
                    : mapper.readValue(rs.getString("headers_json"), new TypeReference<>() {});
            Map<String, Object> variables = rs.getString("variables") == null ? Map.of()
                    : mapper.readValue(rs.getString("variables"), new TypeReference<>() {});
            return new Environment(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                    rs.getString("base_url"), headers, variables);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    };

    public List<Environment> listEnvironments(Long projectId) {
        return jdbc.query("SELECT id, project_id, name, base_url, headers_json, variables FROM api_environment WHERE project_id = ? ORDER BY id",
                ENV_ROW, projectId);
    }

    public Environment findEnvironment(Long id) {
        List<Environment> rows = jdbc.query(
                "SELECT id, project_id, name, base_url, headers_json, variables FROM api_environment WHERE id = ?",
                ENV_ROW, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveEnvironment(Long projectId, Long id, String name, String baseUrl, Map<String, String> headers,
                                Map<String, Object> variables) {
        try {
            if (id == null) {
                jdbc.update("INSERT INTO api_environment(project_id, name, base_url, headers_json, variables) VALUES (?, ?, ?, ?::jsonb, ?::jsonb)",
                        projectId, name, baseUrl,
                        mapper.writeValueAsString(headers == null ? Map.of() : headers),
                        mapper.writeValueAsString(variables == null ? Map.of() : variables));
            } else {
                jdbc.update("UPDATE api_environment SET name = ?, base_url = ?, headers_json = ?::jsonb, variables = ?::jsonb WHERE id = ? AND project_id = ?",
                        name, baseUrl, mapper.writeValueAsString(headers == null ? Map.of() : headers),
                        mapper.writeValueAsString(variables == null ? Map.of() : variables), id, projectId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存环境失败: " + e.getMessage(), e);
        }
    }

    public void deleteEnvironment(Long id) {
        jdbc.update("DELETE FROM api_environment WHERE id = ?", id);
    }

    /* ---------------- test case ---------------- */

    public record TestCase(Long id, Long projectId, Long endpointId, String name, Map<String, Object> request,
                           Map<String, Object> response, Integer expectedStatus, Integer lastStatus,
                           Integer lastDurationMs) {}

    private static final RowMapper<TestCase> CASE_ROW = (rs, i) -> {
        try {
            return new TestCase(rs.getLong("id"), rs.getLong("project_id"), getLong(rs, "endpoint_id"),
                    rs.getString("name"), readMap(rs, "request_json"), readMap(rs, "response_json"),
                    (Integer) rs.getObject("expected_status"), (Integer) rs.getObject("last_status"),
                    (Integer) rs.getObject("last_duration_ms"));
        } catch (Exception e) {
            throw new SQLException(e);
        }
    };

    public List<TestCase> listTestCases(Long projectId) {
        return jdbc.query("SELECT id, project_id, endpoint_id, name, request_json, response_json, expected_status, last_status, last_duration_ms FROM api_test_case WHERE project_id = ? ORDER BY updated_at DESC",
                CASE_ROW, projectId);
    }

    public void saveTestCase(Long projectId, Long id, Long endpointId, String name, Map<String, Object> request,
                             Map<String, Object> response, Integer expectedStatus, Integer lastStatus, Integer lastDurationMs) {
        try {
            if (id == null) {
                jdbc.update("""
                        INSERT INTO api_test_case(project_id, endpoint_id, name, request_json, response_json,
                            expected_status, last_status, last_duration_ms, updated_at)
                        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, now())
                        """, projectId, endpointId, name, mapper.writeValueAsString(request),
                        jsonOrNull(response), expectedStatus, lastStatus, lastDurationMs);
            } else {
                jdbc.update("""
                        UPDATE api_test_case SET name = ?, endpoint_id = COALESCE(?, endpoint_id),
                            request_json = COALESCE(?::jsonb, request_json), response_json = COALESCE(?::jsonb, response_json),
                            expected_status = COALESCE(?, expected_status),
                            last_status = COALESCE(?, last_status), last_duration_ms = COALESCE(?, last_duration_ms),
                            updated_at = now()
                        WHERE id = ? AND project_id = ?
                        """, name, endpointId, jsonOrNull(request), jsonOrNull(response), expectedStatus,
                        lastStatus, lastDurationMs, id, projectId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存用例失败: " + e.getMessage(), e);
        }
    }

    public void deleteTestCase(Long id) {
        jdbc.update("DELETE FROM api_test_case WHERE id = ?", id);
    }

    private String jsonOrNull(Object o) throws Exception {
        return o == null ? null : mapper.writeValueAsString(o);
    }
}