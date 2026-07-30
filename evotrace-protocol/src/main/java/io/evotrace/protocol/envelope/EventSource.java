package io.evotrace.protocol.envelope;

public enum EventSource {
    GITLAB_WEBHOOK,
    GITHUB_WEBHOOK,
    GITEE_WEBHOOK,
    JAVA_SDK,
    CLI,
    OPEN_API,
    JIRA,
    FEISHU
}
