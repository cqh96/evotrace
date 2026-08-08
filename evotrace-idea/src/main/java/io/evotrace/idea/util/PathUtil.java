package io.evotrace.idea.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

public final class PathUtil {

    private PathUtil() {
    }

    public static String relativePath(Project project, VirtualFile file) {
        if (file == null) {
            return null;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile base = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (base != null) {
                String rel = VfsUtilCore.getRelativePath(file, base, '/');
                if (rel != null && !rel.isBlank()) {
                    return rel;
                }
            }
        }
        return file.getPath();
    }
}
