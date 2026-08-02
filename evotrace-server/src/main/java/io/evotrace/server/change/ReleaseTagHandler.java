package io.evotrace.server.change;

import io.evotrace.protocol.envelope.Envelope;
import io.evotrace.protocol.envelope.EventType;
import io.evotrace.server.application.Application;
import io.evotrace.server.application.ApplicationRepository;
import io.evotrace.server.project.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles RELEASE_TAG events (GitLab tag_push / GitHub tag create):
 * writes a {@code release} row (closing the release lifecycle loop that
 * previously could only be seeded) and persists the event for the timeline.
 */
@Component
public class ReleaseTagHandler extends SimpleEventHandler {

    /** Matches "tag: v2.5.0" style messages injected by the webhook adapters. */
    private static final Pattern TAG_PATTERN =
            Pattern.compile("tag:\\s*(v?[\\w.\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter FALLBACK_VERSION = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final JdbcTemplate jdbc;
    private final ApplicationRepository applicationRepository;

    public ReleaseTagHandler(ChangeEventRepository changeEventRepository,
                             ProjectRepository projectRepository,
                             JdbcTemplate jdbc,
                             ApplicationRepository applicationRepository) {
        super(changeEventRepository, projectRepository);
        this.jdbc = jdbc;
        this.applicationRepository = applicationRepository;
    }

    @Override
    public EventType supportedType() {
        return EventType.RELEASE_TAG;
    }

    @Override
    protected ChangeEvent buildEvent(Envelope envelope, Long projectId) {
        ChangeEvent event = super.buildEvent(envelope, projectId);

        Long appId = null;
        if (envelope.appKey() != null) {
            appId = applicationRepository.findByProjectIdAndAppKey(projectId, envelope.appKey())
                    .map(Application::getId).orElse(null);
        }
        event.setAppId(appId);

        upsertRelease(projectId, appId, envelope);
        return event;
    }

    private void upsertRelease(Long projectId, Long appId, Envelope envelope) {
        String version = parseVersion(envelope);
        String baseCommit = envelope.payload() != null ? (String) envelope.payload().get("commitSha") : null;
        String tag = version;
        String env = payloadString(envelope, "env");
        if (env == null || env.isBlank()) env = "prod";

        if (appId != null) {
            // app_id is part of the unique key → plain upsert works
            jdbc.update("""
                    INSERT INTO release(project_id, app_id, version, base_commit, tag, env, status, released_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'RELEASED', ?)
                    ON CONFLICT (project_id, app_id, version) DO UPDATE SET
                        base_commit = EXCLUDED.base_commit,
                        tag = EXCLUDED.tag,
                        env = EXCLUDED.env,
                        released_at = EXCLUDED.released_at
                    """, projectId, appId, version, baseCommit, tag, env, envelope.occurredAt());
            return;
        }

        // app_id is NULL: the UNIQUE(project_id, app_id, version) constraint does
        // not apply to NULLs in PostgreSQL, so ON CONFLICT can't be used.
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM release WHERE project_id = ? AND app_id IS NULL AND version = ?",
                projectId, version);
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO release(project_id, app_id, version, base_commit, tag, env, status, released_at)
                    VALUES (?, NULL, ?, ?, ?, ?, 'RELEASED', ?)
                    """, projectId, version, baseCommit, tag, env, envelope.occurredAt());
        } else {
            jdbc.update("""
                    UPDATE release SET base_commit = ?, tag = ?, env = ?, released_at = ?
                    WHERE id = ?
                    """, baseCommit, tag, env, envelope.occurredAt(), existing.get(0).get("id"));
        }
    }

    private String parseVersion(Envelope envelope) {
        String message = payloadString(envelope, "message");
        if (message != null) {
            Matcher m = TAG_PATTERN.matcher(message);
            if (m.find()) return m.group(1);
        }
        // Fallback: date-based version
        return envelope.occurredAt().toLocalDate().format(FALLBACK_VERSION);
    }
}
