-- EvoTrace core loop completion (V6)
-- 1) commit_message: AI commit detection (CodeReview) needs the actual
--    commit message; previously only author/ai_semantic_unit were available.
ALTER TABLE change_event ADD COLUMN commit_message TEXT;

-- 2) hmac_key: plaintext API secret needed to verify HMAC signatures
--    (secret_hash is a BCrypt hash and cannot be reversed).
--    NOTE: existing credentials have NULL hmac_key — rotate them once.
ALTER TABLE api_credential ADD COLUMN hmac_key VARCHAR(128);

-- 3) inventory_report: persists INVENTORY_REPORT payloads — the data
--    source for the snapshot engine (per-version API/dependency/config/DDL
--    inventories that feed snapshot_item with categories API/DEPENDENCY/CONFIG/SCHEMA).
CREATE TABLE inventory_report (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    app_id          BIGINT NOT NULL REFERENCES application(id),
    event_id        VARCHAR(64) NOT NULL UNIQUE,
    base_commit     VARCHAR(64),
    version         VARCHAR(64),
    tech_stack      VARCHAR(64),
    api_json        JSONB NOT NULL DEFAULT '[]',
    dependency_json JSONB NOT NULL DEFAULT '[]',
    config_json     JSONB NOT NULL DEFAULT '{}',
    ddl_json        JSONB NOT NULL DEFAULT '[]',
    reported_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_inventory_app_time ON inventory_report (app_id, reported_at DESC);

-- 4) Dedup for breaking change alerts: both the scheduled snapshot engine
--    and the manual risk-score endpoint may detect the same change.
CREATE UNIQUE INDEX uq_bca_project_type_msg
    ON breaking_change_alert (project_id, change_type, (detail_json ->> 'message'));
