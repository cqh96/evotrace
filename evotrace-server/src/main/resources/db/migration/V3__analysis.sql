-- EvoTrace analysis & subscription engine schema (M2)

-- API dependency graph for impact analysis
CREATE TABLE api_dependency_graph (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    caller       VARCHAR(512) NOT NULL,       -- e.g. "order-service:POST /order/create"
    callee       VARCHAR(512) NOT NULL,       -- e.g. "payment-service:POST /pay/charge"
    call_type    VARCHAR(32) NOT NULL DEFAULT 'REST',  -- REST / RPC / MQ / DB
    detected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, caller, callee)
);
CREATE INDEX idx_api_dep_project ON api_dependency_graph (project_id);

-- Subscription rules for smart notifications
CREATE TABLE subscription_rule (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT NOT NULL REFERENCES workspace(id),
    user_id      BIGINT NOT NULL REFERENCES sys_user(id),
    name         VARCHAR(128) NOT NULL,
    filter_json  JSONB NOT NULL,              -- {"projectKey":"mall","eventTypes":["DDL_CHANGE"],"filePattern":"**/payment/**"}
    channel      VARCHAR(32) NOT NULL DEFAULT 'FEISHU',  -- FEISHU / DINGTALK / WECHAT / EMAIL / WEBHOOK
    webhook_url  VARCHAR(512),
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscription_user ON subscription_rule (user_id);

-- Breaking change alerts
CREATE TABLE breaking_change_alert (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES project(id),
    release_id    BIGINT REFERENCES release(id),
    change_type   VARCHAR(64) NOT NULL,         -- API_DELETED / FIELD_NARROWED / DDL_DROP_COLUMN / DEP_MAJOR_UPGRADE
    severity      VARCHAR(16) NOT NULL DEFAULT 'WARNING', -- CRITICAL / WARNING / INFO
    detail_json   JSONB NOT NULL,
    acknowledged  BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bca_project ON breaking_change_alert (project_id);

-- Release risk scores
CREATE TABLE release_risk_score (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    release_id      BIGINT NOT NULL REFERENCES release(id) UNIQUE,
    total_score     INT NOT NULL DEFAULT 0,          -- 0-100
    change_volume   INT,                              -- sub-score
    breaking_change INT,                              -- sub-score
    historical_bugs INT,                              -- sub-score
    impact_radius   INT,                              -- sub-score
    time_factor     INT,                              -- sub-score
    explanation     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Notification log
CREATE TABLE notification_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id      BIGINT REFERENCES subscription_rule(id),
    channel      VARCHAR(32) NOT NULL,
    event_id     VARCHAR(64),
    title        VARCHAR(256),
    content      TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'SENT',  -- SENT / FAILED
    error_msg    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_rule ON notification_log (rule_id);
