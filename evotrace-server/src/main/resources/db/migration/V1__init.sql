-- EvoTrace core schema (MVP subset, PostgreSQL 16 + pgvector)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE workspace (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    plan          VARCHAR(32)  NOT NULL DEFAULT 'community',
    ai_privacy_default VARCHAR(32) NOT NULL DEFAULT 'STRUCTURE_ONLY',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE project (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id   BIGINT NOT NULL REFERENCES workspace(id),
    project_key    VARCHAR(64) NOT NULL,
    name           VARCHAR(128) NOT NULL,
    repo_url       VARCHAR(512),
    default_branch VARCHAR(128) DEFAULT 'main',
    privacy_level  VARCHAR(32) NOT NULL DEFAULT 'STRUCTURE_ONLY',
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, project_key)
);

CREATE TABLE application (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id),
    app_key    VARCHAR(64) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    tech_stack VARCHAR(64),
    owner      VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, app_key)
);

CREATE TABLE api_credential (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id),
    api_key     VARCHAR(64) NOT NULL UNIQUE,
    secret_hash VARCHAR(128) NOT NULL,
    scope       VARCHAR(32) NOT NULL DEFAULT 'INGEST',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE iteration (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    external_key VARCHAR(64),
    title        VARCHAR(512) NOT NULL,
    source       VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    status       VARCHAR(32),
    url          VARCHAR(512),
    synced_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, source, external_key)
);

CREATE TABLE change_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    project_id     BIGINT NOT NULL REFERENCES project(id),
    app_id         BIGINT REFERENCES application(id),
    event_id       VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    event_type     VARCHAR(32) NOT NULL,
    branch         VARCHAR(255),
    commit_sha     VARCHAR(64),
    author         VARCHAR(128),
    iteration_id   BIGINT REFERENCES iteration(id),
    blob_ref       VARCHAR(512),
    summary_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    occurred_at    TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, occurred_at),
    UNIQUE (idempotency_key, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE change_event_2026_07 PARTITION OF change_event
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE INDEX idx_change_event_timeline ON change_event (project_id, occurred_at DESC);
CREATE INDEX idx_change_event_commit ON change_event (project_id, commit_sha);

CREATE TABLE change_file (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      VARCHAR(64) NOT NULL,
    file_path     VARCHAR(1024) NOT NULL,
    old_path      VARCHAR(1024),
    change_kind   VARCHAR(16) NOT NULL,
    add_lines     INT DEFAULT 0,
    del_lines     INT DEFAULT 0,
    diff_blob_ref VARCHAR(512),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_change_file_path ON change_file (file_path);

CREATE TABLE release (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id),
    app_id      BIGINT REFERENCES application(id),
    version     VARCHAR(64) NOT NULL,
    base_commit VARCHAR(64),
    tag         VARCHAR(128),
    env         VARCHAR(32),
    status      VARCHAR(16) NOT NULL DEFAULT 'RELEASED',
    released_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, app_id, version)
);

CREATE TABLE snapshot (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    release_id  BIGINT NOT NULL REFERENCES release(id),
    app_id      BIGINT NOT NULL REFERENCES application(id),
    type        VARCHAR(8) NOT NULL,          -- FULL / DELTA
    baseline_id BIGINT REFERENCES snapshot(id),
    item_count  INT DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE snapshot_item (
    content_hash VARCHAR(64) PRIMARY KEY,      -- content-addressed, platform-wide dedup
    category     VARCHAR(32) NOT NULL,          -- API / DEPENDENCY / CONFIG / SCHEMA / STRUCTURE
    identity_key VARCHAR(1024) NOT NULL,
    content_json JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE snapshot_item_ref (
    snapshot_id BIGINT NOT NULL REFERENCES snapshot(id),
    item_hash   VARCHAR(64) NOT NULL REFERENCES snapshot_item(content_hash),
    change_flag VARCHAR(12) NOT NULL,           -- ADDED / REMOVED / UNCHANGED
    PRIMARY KEY (snapshot_id, item_hash)
);

CREATE TABLE ai_semantic_unit (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type  VARCHAR(32) NOT NULL,          -- CHANGE_EVENT / RELEASE / ITERATION
    target_id    VARCHAR(64) NOT NULL,
    kind         VARCHAR(32) NOT NULL,          -- SUMMARY / RELEASE_NOTE / IMPACT / QA
    content      TEXT NOT NULL,
    model        VARCHAR(64),
    confidence   NUMERIC(4,3),
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_semantic_target ON ai_semantic_unit (target_type, target_id);

CREATE TABLE ai_embedding (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type VARCHAR(32) NOT NULL,
    target_id   VARCHAR(64) NOT NULL,
    model       VARCHAR(64),
    embedding   vector(1024),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (target_type, target_id)
);

CREATE TABLE ai_usage (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id      BIGINT NOT NULL REFERENCES workspace(id),
    model             VARCHAR(64),
    task_type         VARCHAR(32),
    prompt_tokens     INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    cost              NUMERIC(12,6) DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor      VARCHAR(64),
    action     VARCHAR(64) NOT NULL,
    target     VARCHAR(256),
    detail     JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
