package io.evotrace.server.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "change_event")
public class ChangeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "app_id")
    private Long appId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(length = 255)
    private String branch;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(length = 128)
    private String author;

    @Column(name = "iteration_id")
    private Long iterationId;

    @Column(name = "blob_ref", length = 512)
    private String blobRef;

    @Column(name = "summary_status", nullable = false, length = 16)
    private String summaryStatus = "PENDING";

    @Column(name = "commit_message")
    private String commitMessage;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Long getIterationId() { return iterationId; }
    public void setIterationId(Long iterationId) { this.iterationId = iterationId; }

    public String getBlobRef() { return blobRef; }
    public void setBlobRef(String blobRef) { this.blobRef = blobRef; }

    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }

    public String getCommitMessage() { return commitMessage; }
    public void setCommitMessage(String commitMessage) { this.commitMessage = commitMessage; }

    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
