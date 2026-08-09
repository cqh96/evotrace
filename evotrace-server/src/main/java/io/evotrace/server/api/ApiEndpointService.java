package io.evotrace.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the API workspace: syncs endpoints from latest per-app inventory
 * reports, and imports external API definitions (OpenAPI/Postman/cURL/Apifox)
 * into the per-project endpoint store.
 */
@Service
public class ApiEndpointService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ApiRepository apiRepository;
    private final ApiImporter importer;

    public ApiEndpointService(JdbcTemplate jdbc, ApiRepository apiRepository, ApiImporter importer) {
        this.jdbc = jdbc;
        this.apiRepository = apiRepository;
        this.importer = importer;
    }

    /** Pull the latest inventory API list per app into api_endpoint. */
    @Transactional
    public int syncFromInventory(Long projectId) {
        List<Map<String, Object>> reports = jdbc.queryForList("""
                SELECT DISTINCT ON (app_id) app_id, api_json
                FROM inventory_report
                WHERE project_id = ?
                ORDER BY app_id, reported_at DESC
                """, projectId);
        int count = 0;
        for (Map<String, Object> r : reports) {
            Long appId = ((Number) r.get("app_id")).longValue();
            Object apiJson = r.get("api_json");
            if (apiJson == null) continue;
            List<Map<String, Object>> apis;
            try {
                apis = mapper.readValue(String.valueOf(apiJson), new TypeReference<>() {});
            } catch (Exception e) {
                continue;
            }
            for (Map<String, Object> api : apis) {
                String method = String.valueOf(api.getOrDefault("httpMethod", "GET")).toUpperCase();
                String path = String.valueOf(api.getOrDefault("path", ""));
                if (path.isBlank()) continue;
                List<Map<String, Object>> params = readParams(api.get("params"));
                Map<String, Object> requestBody = readMap(api.get("requestSchema"));
                Map<String, Object> responseSchema = readMap(api.get("responseSchema"));
                apiRepository.upsert(projectId, appId, method, path,
                        String.valueOf(api.getOrDefault("name", "")), null,
                        readTags(api.get("tags")), params, requestBody, responseSchema, null, "INVENTORY");
                count++;
            }
        }
        return count;
    }

    @Transactional
    public int importDrafts(Long projectId, Long appId, String format, String content) {
        List<ApiImporter.Draft> drafts = importer.importFile(format, content);
        for (ApiImporter.Draft d : drafts) {
            apiRepository.upsert(projectId, appId, d.method().toUpperCase(), d.path(),
                    d.name(), d.summary(), d.tags(), d.params(), d.requestBody(),
                    d.responseSchema(), d.mockResponse(), "IMPORT");
        }
        return drafts.size();
    }

    public List<ApiRepository.Endpoint> list(Long projectId) {
        return apiRepository.listByProject(projectId);
    }

    public ApiRepository.Endpoint get(Long id) {
        return apiRepository.findById(id);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readParams(Object o) {
        if (o == null) return List.of();
        try {
            return mapper.convertValue(o, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
            return mapper.readValue(String.valueOf(o), new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readTags(Object o) {
        if (o == null) return List.of();
        try {
            return mapper.convertValue(o, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}