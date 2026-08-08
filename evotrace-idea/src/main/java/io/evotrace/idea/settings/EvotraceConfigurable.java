package io.evotrace.idea.settings;

import com.intellij.openapi.options.Configurable;
import io.evotrace.idea.api.EvotraceClient;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class EvotraceConfigurable implements Configurable {

    private EvotraceSettingsPanel panel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "EvoTrace";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new EvotraceSettingsPanel();
        return panel.getComponent();
    }

    @Override
    public boolean isModified() {
        EvotraceSettings s = EvotraceSettings.getInstance();
        return !panel.getServerUrl().equals(nullTo(s.serverUrl))
                || !panel.getProjectKey().equals(nullTo(s.projectKey))
                || !panel.getUsername().equals(nullTo(s.username))
                || !panel.getPassword().equals(nullTo(s.password));
    }

    @Override
    public void apply() {
        EvotraceSettings s = EvotraceSettings.getInstance();
        s.serverUrl = panel.getServerUrl().trim();
        s.projectKey = panel.getProjectKey().trim();
        s.username = panel.getUsername().trim();
        s.password = panel.getPassword();
        EvotraceClient.clearToken();
    }

    @Override
    public void reset() {
        EvotraceSettings s = EvotraceSettings.getInstance();
        panel.setServerUrl(s.serverUrl);
        panel.setProjectKey(s.projectKey);
        panel.setUsername(s.username);
        panel.setPassword(s.password);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }

    private static String nullTo(String v) {
        return v == null ? "" : v;
    }
}
