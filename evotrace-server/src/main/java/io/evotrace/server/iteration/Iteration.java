package io.evotrace.server.iteration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "iteration")
public class Iteration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "external_key", length = 64)
    private String externalKey;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false, length = 32)
    private String source = "MANUAL";

    @Column(length = 32)
    private String status;

    @Column(length = 512)
    private String url;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
