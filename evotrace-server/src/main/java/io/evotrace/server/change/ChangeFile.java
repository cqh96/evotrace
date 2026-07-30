package io.evotrace.server.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "change_file")
public class ChangeFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(name = "old_path", length = 1024)
    private String oldPath;

    @Column(name = "change_kind", nullable = false, length = 16)
    private String changeKind;

    @Column(name = "add_lines")
    private int addLines;

    @Column(name = "del_lines")
    private int delLines;

    @Column(name = "diff_blob_ref", length = 512)
    private String diffBlobRef;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getOldPath() { return oldPath; }
    public void setOldPath(String oldPath) { this.oldPath = oldPath; }

    public String getChangeKind() { return changeKind; }
    public void setChangeKind(String changeKind) { this.changeKind = changeKind; }

    public int getAddLines() { return addLines; }
    public void setAddLines(int addLines) { this.addLines = addLines; }

    public int getDelLines() { return delLines; }
    public void setDelLines(int delLines) { this.delLines = delLines; }

    public String getDiffBlobRef() { return diffBlobRef; }
    public void setDiffBlobRef(String diffBlobRef) { this.diffBlobRef = diffBlobRef; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
