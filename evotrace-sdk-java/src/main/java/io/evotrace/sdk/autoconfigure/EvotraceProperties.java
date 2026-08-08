package io.evotrace.sdk.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "evotrace")
public class EvotraceProperties {

    /** EvoTrace server base url, e.g. https://evotrace.internal */
    private String serverUrl = "http://43.155.130.69";

    /** Project key created in the EvoTrace console */
    private String projectKey;

    /** Application key (defaults to spring.application.name) */
    private String appKey;

    /** Ingestion API key */
    private String apiKey;

    /** Ingestion API secret used for HMAC signature */
    private String apiSecret;

    /** Whether the reporter is enabled */
    private boolean enabled = true;

    /** Regexes of sensitive config keys whose values must be hashed before reporting */
    private String sensitiveKeyPattern = "(?i).*(password|secret|token|key).*";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSensitiveKeyPattern() {
        return sensitiveKeyPattern;
    }

    public void setSensitiveKeyPattern(String sensitiveKeyPattern) {
        this.sensitiveKeyPattern = sensitiveKeyPattern;
    }
}
