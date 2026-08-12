package io.evotrace.protocol.envelope;

public enum EventSource {
    GITLAB_WEBHOOK,
    GITLAB_REPO,         // V2.5: GitLab 仓库主动导入
    GITHUB_WEBHOOK,
    GITEE_WEBHOOK,
    JAVA_SDK,
    CLI,
    OPEN_API,
    JIRA,
    FEISHU
}
