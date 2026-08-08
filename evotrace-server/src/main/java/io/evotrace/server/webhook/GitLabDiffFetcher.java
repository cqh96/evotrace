package io.evotrace.server.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.ingestion.BlobStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches per-file unified diffs from GitLab REST API and stores them as blobs
 * so webhook-ingested CODE_COMMIT events can expose real code changes.
 */
@Component
public class GitLabDiffFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitLabDiffFetcher.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final BlobStoreService blobStore;
    private final HttpClient httpClient;

    @Value("${evotrace.gitlab.token:}")
    private String token;

    @Value("${evotrace.gitlab.base-url:}")
    private String baseUrlOverride;

    @Value("${evotrace.gitlab.fetch-diff:true}")
    private boolean fetchDiff;

    @Value("${evotrace.gitlab.max-diff-chars-per-file:200000}")
    private int maxDiffCharsPerFile;

    public GitLabDiffFetcher(BlobStoreService blobStore) {
        this.blobStore = blobStore;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @return enriched file changes with line counts + diffBlobRef; empty if
     *         fetch is disabled / token missing / API failed (caller falls back).
     */
    public List<CodeCommitPayload.FileChange> fetchCommitFiles(Object projectId,
                                                                String commitSha,
                                                                String webUrl) {
        if (!fetchDiff) {
            return List.of();
        }
        if (token == null || token.isBlank()) {
            log.warn("evotrace.gitlab.token not set — skip fetching commit diffs");
            return List.of();
        }
        if (projectId == null || commitSha == null || commitSha.isBlank()) {
            return List.of();
        }

        String base = resolveBaseUrl(webUrl);
        if (base == null) {
            log.warn("cannot resolve GitLab base URL from webUrl={}", webUrl);
            return List.of();
        }

        try {
            List<JsonNode> diffs = fetchAllDiffPages(base, String.valueOf(projectId), commitSha);
            if (diffs.isEmpty()) {
                return List.of();
            }
            List<CodeCommitPayload.FileChange> files = new ArrayList<>(diffs.size());
            for (JsonNode d : diffs) {
                files.add(toFileChange(d));
            }
            log.info("fetched {} file diffs from GitLab for commit {}", files.size(),
                    commitSha.substring(0, Math.min(8, commitSha.length())));
            return files;
        } catch (Exception e) {
            log.warn("failed to fetch GitLab commit diff project={} sha={}: {}",
                    projectId, commitSha, e.getMessage());
            return List.of();
        }
    }

    private List<JsonNode> fetchAllDiffPages(String base, String projectId, String commitSha)
            throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String encodedProject = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        String encodedSha = URLEncoder.encode(commitSha, StandardCharsets.UTF_8);
        String nextUrl = base + "/api/v4/projects/" + encodedProject
                + "/repository/commits/" + encodedSha + "/diff?per_page=100";

        for (int page = 0; page < 20 && nextUrl != null; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(nextUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 200));
            }
            JsonNode arr = mapper.readTree(response.body());
            if (!arr.isArray()) {
                throw new IllegalStateException("unexpected GitLab diff response");
            }
            arr.forEach(all::add);

            String nextPage = response.headers().firstValue("X-Next-Page").orElse("");
            if (nextPage.isBlank()) {
                nextUrl = null;
            } else {
                nextUrl = base + "/api/v4/projects/" + encodedProject
                        + "/repository/commits/" + encodedSha
                        + "/diff?per_page=100&page=" + nextPage;
            }
        }
        return all;
    }

    private CodeCommitPayload.FileChange toFileChange(JsonNode d) {
        String oldPath = text(d, "old_path");
        String newPath = text(d, "new_path");
        boolean deleted = d.path("deleted_file").asBoolean(false);
        boolean added = d.path("new_file").asBoolean(false);
        boolean renamed = d.path("renamed_file").asBoolean(false);

        CodeCommitPayload.FileChange.ChangeKind kind;
        if (deleted) {
            kind = CodeCommitPayload.FileChange.ChangeKind.DELETED;
        } else if (added) {
            kind = CodeCommitPayload.FileChange.ChangeKind.ADDED;
        } else if (renamed) {
            kind = CodeCommitPayload.FileChange.ChangeKind.RENAMED;
        } else {
            kind = CodeCommitPayload.FileChange.ChangeKind.MODIFIED;
        }

        String diff = text(d, "diff");
        if (diff == null) {
            diff = "";
        }
        if (diff.length() > maxDiffCharsPerFile) {
            diff = diff.substring(0, maxDiffCharsPerFile) + "\n\n… [diff truncated]";
        }

        int[] lines = countLines(diff);
        String blobRef = null;
        if (!diff.isBlank()) {
            blobRef = blobStore.put(diff);
        }

        return new CodeCommitPayload.FileChange(
                deleted ? (oldPath != null ? oldPath : newPath) : oldPath,
                deleted ? null : (newPath != null ? newPath : oldPath),
                kind,
                lines[0],
                lines[1],
                blobRef
        );
    }

    /** @return [addLines, delLines] */
    static int[] countLines(String diff) {
        int add = 0;
        int del = 0;
        if (diff == null || diff.isEmpty()) {
            return new int[]{0, 0};
        }
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                add++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                del++;
            }
        }
        return new int[]{add, del};
    }

    private String resolveBaseUrl(String webUrl) {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return trimTrailingSlash(baseUrlOverride.trim());
        }
        if (webUrl == null || webUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(webUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://").append(uri.getHost());
            if (uri.getPort() > 0) {
                sb.append(':').append(uri.getPort());
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
