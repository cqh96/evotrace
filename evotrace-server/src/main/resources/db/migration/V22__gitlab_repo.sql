-- V22: GitLab 仓库集成（V2.5）

-- GitLab 连接配置（按项目）
CREATE TABLE IF NOT EXISTS gitlab_connection (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id        BIGINT NOT NULL REFERENCES project(id),
    base_url          VARCHAR(512) NOT NULL,             -- GitLab 实例地址
    auth_type         VARCHAR(16)  NOT NULL DEFAULT 'PAT', -- PAT / GROUP_TOKEN / PROJECT_TOKEN
    token_enc         TEXT NOT NULL,                      -- 加密存储的访问令牌
    default_namespace VARCHAR(128),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, base_url)
);

-- 仓库导入
CREATE TABLE IF NOT EXISTS repo_import (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    repo_path        VARCHAR(512) NOT NULL,              -- namespace/repo
    default_branch   VARCHAR(128) DEFAULT 'main',
    clone_status     VARCHAR(16)  DEFAULT 'PENDING',     -- PENDING / CLONING / SYNCED / FAILED
    last_synced_sha  VARCHAR(64),
    schedule_cron    VARCHAR(64),                        -- 增量同步计划
    local_path       VARCHAR(512),                       -- 本地裸仓库路径
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, repo_path)
);

-- 仓库同步批次日志
CREATE TABLE IF NOT EXISTS repo_import_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo_id       BIGINT NOT NULL REFERENCES repo_import(id),
    sync_type     VARCHAR(16) NOT NULL,                  -- FULL / INCREMENTAL
    status        VARCHAR(16) NOT NULL,                  -- SUCCESS / FAILED
    commits_count INTEGER    DEFAULT 0,
    message       TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);