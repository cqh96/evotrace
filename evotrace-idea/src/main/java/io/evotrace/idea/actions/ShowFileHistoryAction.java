package io.evotrace.idea.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import io.evotrace.idea.ui.EvotracePanel;
import io.evotrace.idea.ui.EvotraceToolWindows;
import io.evotrace.idea.util.PathUtil;
import org.jetbrains.annotations.NotNull;

public class ShowFileHistoryAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || file.isDirectory()) {
            return;
        }
        String rel = PathUtil.relativePath(project, file);
        EvotracePanel panel = EvotraceToolWindows.openPanel(project);
        if (panel != null) {
            panel.showFileHistory(rel);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(
                e.getProject() != null && file != null && !file.isDirectory());
    }

    @Override
    public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
    }
}
