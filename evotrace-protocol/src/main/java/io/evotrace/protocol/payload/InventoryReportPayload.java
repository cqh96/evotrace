package io.evotrace.protocol.payload;

import java.util.List;
import java.util.Map;

public record InventoryReportPayload(
        String baseCommit,
        String version,
        String techStack,
        List<ApiItem> apis,
        List<DependencyItem> dependencies,
        Map<String, String> configFingerprints,
        List<String> ddlStatements
) {
    public record ApiItem(String httpMethod, String path, String signatureHash, String schemaFingerprint,
                          String name, List<String> tags, List<Map<String, Object>> params,
                          Map<String, Object> requestSchema, Map<String, Object> responseSchema) {
    }

    public record DependencyItem(String group, String artifact, String version, String scope) {
    }
}
