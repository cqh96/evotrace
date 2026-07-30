package io.evotrace.server.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evotrace.common.Result;
import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventSource;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.protocol.payload.CodeCommitPayload;
import io.evotrace.server.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook adapters for Git providers (GitLab, GitHub, Gitee).
 */
@RestController
@RequestMapping("/open-api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final IngestionService ingestionService;

    public WebhookController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    // ==================== GitLab ====================

    @PostMapping("/gitlab")
    public Result<Map<String, Object>> gitlab(@RequestHeader(value = "X-EvoTrace-Api-Key", required = false) String apiKey,
                                               @RequestHeader(value = "X-EvoTrace-Signature", required = false) String signature,
                                               @RequestBody Map<String, Object> body) {
        String eventType = (String) body.getOrDefault("object_kind", "push");
        Envelope envelope = switch (eventType) {
            case "push" -> parseGitLabPush(body);
            case "merge_request" -> parseGitLabMR(body);
            case "tag_push" -> parseGitLabTag(body);
            default -> null;
        };
        if (envelope == null) {
            return Result.ok(Map.of("skipped", true, "reason", "unsupported event type: " + eventType));
        }
        return ingestionService.accept(nvl(apiKey, "webhook-default"), nvl(signature, ""), envelope);
    }

    @SuppressWarnings("unchecked")
    private Envelope parseGitLabPush(Map<String, Object> body) {
        String projectKey = extractProjectKey(body);
        Map<String, Object> project = (Map<String, Object>) body.get("project");
        String repoUrl = project != null ? (String) project.get("git_http_url") : null;
        String branch = (String) body.get("ref");
        if (branch != null && branch.startsWith("refs/heads/")) {
            branch = branch.substring("refs/heads/".length());
        }
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");
        if (commits == null || commits.isEmpty()) return null;
        Map<String, Object> lastCommit = commits.get(commits.size() - 1);
        Map<String, Object> author = (Map<String, Object>) lastCommit.get("author");

        CodeCommitPayload payload = new CodeCommitPayload(repoUrl, branch,
                (String) lastCommit.get("id"), List.of(),
                author != null ? (String) author.get("name") : "unknown",
                author != null ? (String) author.get("email") : "",
                (String) lastCommit.get("message"), List.of());
        return buildEnvelope(projectKey, EventType.CODE_COMMIT, EventSource.GITLAB_WEBHOOK, payload);
    }

    @SuppressWarnings("unchecked")
    private Envelope parseGitLabMR(Map<String, Object> body) {
        String projectKey = extractProjectKey(body);
        Map<String, Object> attrs = (Map<String, Object>) body.get("object_attributes");
        if (attrs == null) return null;
        String branch = (String) attrs.get("source_branch");
        Map<String, Object> lastCommitMap = (Map<String, Object>) attrs.get("last_commit");
        String sha = lastCommitMap != null ? (String) lastCommitMap.get("id") : null;
        CodeCommitPayload payload = new CodeCommitPayload(null, branch, sha, List.of(),
                "unknown", "", (String) attrs.get("title"), List.of());
        return buildEnvelope(projectKey, EventType.MR_MERGED, EventSource.GITLAB_WEBHOOK, payload);
    }

    private Envelope parseGitLabTag(Map<String, Object> body) {
        String projectKey = extractProjectKey(body);
        String ref = (String) body.get("ref");
        String tag = ref != null && ref.startsWith("refs/tags/")
                ? ref.substring("refs/tags/".length()) : ref;
        CodeCommitPayload payload = new CodeCommitPayload(null, null,
                (String) body.get("checkout_sha"), List.of(),
                (String) body.get("user_username"), "", "tag: " + tag, List.of());
        return buildEnvelope(projectKey, EventType.RELEASE_TAG, EventSource.GITLAB_WEBHOOK, payload);
    }

    // ==================== GitHub ====================

    @PostMapping("/github")
    public Result<Map<String, Object>> github(@RequestHeader(value = "X-EvoTrace-Api-Key", required = false) String apiKey,
                                               @RequestHeader(value = "X-EvoTrace-Signature", required = false) String signature,
                                               @RequestHeader(value = "X-GitHub-Event", required = false) String ghEvent,
                                               @RequestBody Map<String, Object> body) {
        Envelope envelope = switch (ghEvent != null ? ghEvent : "ping") {
            case "push" -> parseGitHubPush(body);
            case "pull_request" -> parseGitHubPR(body);
            case "create" -> parseGitHubCreate(body);
            case "ping" -> null; // GitHub webhook verification
            default -> null;
        };
        if (envelope == null) {
            return Result.ok(Map.of("skipped", true, "reason", "github event: " + ghEvent));
        }
        return ingestionService.accept(nvl(apiKey, "webhook-default"), nvl(signature, ""), envelope);
    }

    @SuppressWarnings("unchecked")
    private Envelope parseGitHubPush(Map<String, Object> body) {
        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectKey = repo != null ? (String) repo.get("name") : "unknown";
        String repoUrl = repo != null ? (String) repo.get("clone_url") : null;
        String ref = (String) body.get("ref");
        String branch = ref != null && ref.startsWith("refs/heads/")
                ? ref.substring("refs/heads/".length()) : ref;

        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("commits");
        if (commits == null || commits.isEmpty()) return null;

        Map<String, Object> lastCommit = commits.get(commits.size() - 1);
        Map<String, Object> author = (Map<String, Object>) lastCommit.get("author");

        CodeCommitPayload payload = new CodeCommitPayload(repoUrl, branch,
                (String) lastCommit.get("id"), List.of(),
                author != null ? (String) author.get("name") : "unknown",
                author != null ? (String) author.get("email") : "",
                (String) lastCommit.get("message"), List.of());
        return buildEnvelope(projectKey, EventType.CODE_COMMIT, EventSource.GITHUB_WEBHOOK, payload);
    }

    @SuppressWarnings("unchecked")
    private Envelope parseGitHubPR(Map<String, Object> body) {
        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectKey = repo != null ? (String) repo.get("name") : "unknown";
        Map<String, Object> pr = (Map<String, Object>) body.get("pull_request");
        if (pr == null) return null;

        String action = (String) body.get("action");
        if (!"closed".equals(action) || !Boolean.TRUE.equals(pr.get("merged"))) return null;

        Map<String, Object> head = (Map<String, Object>) pr.get("head");
        Map<String, Object> user = (Map<String, Object>) pr.get("user");
        CodeCommitPayload payload = new CodeCommitPayload(
                null, head != null ? (String) head.get("ref") : null,
                (String) pr.get("merge_commit_sha"), List.of(),
                user != null ? (String) user.get("login") : "unknown", "",
                (String) pr.get("title"), List.of());
        return buildEnvelope(projectKey, EventType.MR_MERGED, EventSource.GITHUB_WEBHOOK, payload);
    }

    @SuppressWarnings("unchecked")
    private Envelope parseGitHubCreate(Map<String, Object> body) {
        if (!"tag".equals(body.get("ref_type"))) return null;
        Map<String, Object> repo = (Map<String, Object>) body.get("repository");
        String projectKey = repo != null ? (String) repo.get("name") : "unknown";
        Map<String, Object> sender = (Map<String, Object>) body.get("sender");
        CodeCommitPayload payload = new CodeCommitPayload(null, null, null, List.of(),
                sender != null ? (String) sender.get("login") : "unknown", "",
                "tag: " + body.get("ref"), List.of());
        return buildEnvelope(projectKey, EventType.RELEASE_TAG, EventSource.GITHUB_WEBHOOK, payload);
    }

    // ==================== Common ====================

    @SuppressWarnings("unchecked")
    private String extractProjectKey(Map<String, Object> body) {
        Map<String, Object> project = (Map<String, Object>) body.get("project");
        if (project != null) {
            String name = (String) project.get("name");
            if (name != null) return name;
            String path = (String) project.get("path_with_namespace");
            if (path != null) return path.replace("/", "-");
        }
        return "unknown";
    }

    private Envelope buildEnvelope(String projectKey, EventType type, EventSource source,
                                    CodeCommitPayload payload) {
        return new Envelope(Envelope.CURRENT_VERSION, UUID.randomUUID().toString(),
                projectKey, null, type, OffsetDateTime.now(), source,
                source.name().toLowerCase() + ":" + projectKey + ":" + System.currentTimeMillis(),
                mapper.convertValue(payload, Map.class), null);
    }

    private static String nvl(String s, String defaultVal) {
        return s != null && !s.isBlank() ? s : defaultVal;
    }
}
