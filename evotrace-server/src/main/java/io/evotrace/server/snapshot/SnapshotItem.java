package io.evotrace.server.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "snapshot_item")
public class SnapshotItem {

    @Id
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "identity_key", nullable = false, length = 1024)
    private String identityKey;

    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getIdentityKey() { return identityKey; }
    public void setIdentityKey(String identityKey) { this.identityKey = identityKey; }

    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
