package io.evotrace.idea.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

public class EvotraceStatusBarWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NonNls @NotNull String getId() {
        return "EvoTraceStatusBar";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "EvoTrace";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return true;
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new Widget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }

    private static final class Widget implements StatusBarWidget, StatusBarWidget.TextPresentation {
        private final Project project;

        private Widget(@NotNull Project project) {
            this.project = project;
        }

        @Override
        public @NonNls @NotNull String ID() {
            return "EvoTraceStatusBar";
        }

        @Override
        public @Nullable WidgetPresentation getPresentation() {
            return this;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
        }

        @Override
        public void dispose() {
        }

        @Override
        public @NotNull String getText() {
            return "EvoTrace";
        }

        @Override
        public float getAlignment() {
            return 0f;
        }

        @Override
        public @Nullable String getTooltipText() {
            return "打开 EvoTrace 项目面板";
        }

        @Override
        public @Nullable Consumer<MouseEvent> getClickConsumer() {
            return e -> {
                EvotracePanel panel = EvotraceToolWindows.openPanel(project);
                if (panel != null) {
                    panel.showDashboard();
                }
            };
        }
    }
}
