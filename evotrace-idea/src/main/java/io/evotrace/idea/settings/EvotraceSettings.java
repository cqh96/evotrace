package io.evotrace.idea.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "EvotraceSettings", storages = @Storage("evotrace.xml"))
public class EvotraceSettings implements PersistentStateComponent<EvotraceSettings> {

    public String serverUrl = "http://43.155.130.69";
    public String projectKey = "maidao_merchant";
    public String username = "admin";
    public String password = "admin123";

    public static EvotraceSettings getInstance() {
        return ApplicationManager.getApplication().getService(EvotraceSettings.class);
    }

    @Override
    public @Nullable EvotraceSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull EvotraceSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public String normalizedServerUrl() {
        if (serverUrl == null || serverUrl.isBlank()) {
            return "http://43.155.130.69";
        }
        return serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    public String effectiveProjectKey() {
        return projectKey == null || projectKey.isBlank() ? "maidao_merchant" : projectKey;
    }

    public String effectiveUsername() {
        return username == null || username.isBlank() ? "admin" : username;
    }

    public String effectivePassword() {
        return password == null ? "" : password;
    }
}
