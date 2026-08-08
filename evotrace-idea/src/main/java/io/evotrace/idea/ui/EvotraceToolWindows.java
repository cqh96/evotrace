package io.evotrace.idea.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

public final class EvotraceToolWindows {

    private EvotraceToolWindows() {
    }

    public static EvotracePanel openPanel(Project project) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("EvoTrace");
        if (tw != null) {
            tw.activate(null);
        }
        EvotracePanel panel = project.getUserData(EvotraceToolWindowKeys.PANEL);
        if (panel == null && tw != null) {
            // Ensure factory ran
            tw.show();
            panel = project.getUserData(EvotraceToolWindowKeys.PANEL);
        }
        return panel;
    }
}
