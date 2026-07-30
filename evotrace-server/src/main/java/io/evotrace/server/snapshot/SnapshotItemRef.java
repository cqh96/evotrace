package io.evotrace.server.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;

@Entity
@Table(name = "snapshot_item_ref")
@IdClass(SnapshotItemRef.SnapshotItemRefId.class)
public class SnapshotItemRef {

    @Id
    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Id
    @Column(name = "item_hash", nullable = false, length = 64)
    private String itemHash;

    @Column(name = "change_flag", nullable = false, length = 12)
    private String changeFlag;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }

    public String getItemHash() { return itemHash; }
    public void setItemHash(String itemHash) { this.itemHash = itemHash; }

    public String getChangeFlag() { return changeFlag; }
    public void setChangeFlag(String changeFlag) { this.changeFlag = changeFlag; }

    public static class SnapshotItemRefId implements Serializable {
        private Long snapshotId;
        private String itemHash;

        public SnapshotItemRefId() {}
        public SnapshotItemRefId(Long snapshotId, String itemHash) {
            this.snapshotId = snapshotId;
            this.itemHash = itemHash;
        }

        public Long getSnapshotId() { return snapshotId; }
        public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }

        public String getItemHash() { return itemHash; }
        public void setItemHash(String itemHash) { this.itemHash = itemHash; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SnapshotItemRefId that)) return false;
            return snapshotId.equals(that.snapshotId) && itemHash.equals(that.itemHash);
        }

        @Override
        public int hashCode() {
            return 31 * snapshotId.hashCode() + itemHash.hashCode();
        }
    }
}
