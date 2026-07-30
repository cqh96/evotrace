package io.evotrace.protocol.payload;

import java.util.List;

public record CodeCommitPayload(
        String repoUrl,
        String branch,
        String commitSha,
        List<String> parentShas,
        String authorName,
        String authorEmail,
        String message,
        List<FileChange> files
) {
    public record FileChange(
            String oldPath,
            String newPath,
            ChangeKind kind,
            int addLines,
            int delLines,
            String diffBlobRef
    ) {
        public enum ChangeKind {ADDED, MODIFIED, DELETED, RENAMED}
    }
}
