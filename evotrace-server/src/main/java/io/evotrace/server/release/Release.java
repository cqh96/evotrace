package io.evotrace.server.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "release")
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "app_id")
    private Long appId;

    @Column(nullable = false, length = 64)
    private String version;

    @Column(name = "base_commit", length = 64)
    private String baseCommit;

    @Column(length = 128)
    private String tag;

    @Column(length = 32)
    private String env;

    @Column(nullable = false, length = 16)
    private String status = "RELEASED";

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String baseCommit) { this.baseCommit = baseCommit; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
