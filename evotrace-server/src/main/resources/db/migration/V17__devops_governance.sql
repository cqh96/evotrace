-- ==================== DevOps 治理增强 (V17) ====================
-- P0-3 自动化规则引擎 / P1 角色权限细分 / P2 AI反馈转需求

-- ==================== P0-3：自动化规则引擎 ====================
CREATE TABLE automation_rule (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES project(id),
    name           VARCHAR(256) NOT NULL,
    trigger_event  VARCHAR(32) NOT NULL,
    -- CHANGE_AI_SUMMARY / API_BROKEN / TEST_FAILED / BUG_FOUND / MR_MERGED
    action         VARCHAR(32) NOT NULL,
    -- NOTIFY / CREATE_BUG / AUTO_ASSIGN / AI_ANALYZE
    condition_json JSONB NOT NULL DEFAULT '{}',
    config_json    JSONB NOT NULL DEFAULT '{}',
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    run_count      INT NOT NULL DEFAULT 0,
    last_run_at    TIMESTAMPTZ,
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auto_rule_proj ON automation_rule (project_id, enabled);

-- 规则执行日志（可观测）
CREATE TABLE automation_rule_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id     BIGINT NOT NULL REFERENCES automation_rule(id) ON DELETE CASCADE,
    trigger_event VARCHAR(32) NOT NULL,
    matched     BOOLEAN NOT NULL DEFAULT false,
    result_json JSONB NOT NULL DEFAULT '{}',
    error_msg   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auto_rule_log ON automation_rule_log (rule_id, created_at DESC);

-- ==================== P1：项目成员角色权限 ====================
CREATE TABLE project_member (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id),
    user_id    BIGINT NOT NULL REFERENCES sys_user(id),
    role       VARCHAR(16) NOT NULL DEFAULT 'DEVELOPER',
    -- ADMIN / PM / DEVELOPER / QA / OPS
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, user_id)
);
CREATE INDEX idx_proj_member ON project_member (project_id, role);

-- ==================== P2：反馈 → 需求/缺陷（AI 语义转结构化） ====================
CREATE TABLE feedback (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id              BIGINT NOT NULL REFERENCES project(id),
    source                  VARCHAR(32) DEFAULT 'MANUAL',  -- MANUAL / WEBHOOK / AI
    content                 TEXT NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'NEW',  -- NEW / CONVERTED / IGNORED
    ai_analysis             TEXT,
    ai_model                VARCHAR(64),
    converted_requirement_id BIGINT REFERENCES requirement(id),
    converted_bug_id        BIGINT REFERENCES bug_ticket(id),
    created_by              VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_feedback_proj ON feedback (project_id, status);

-- ==================== P0-1：研效度量快照（定时/手动生成的指标快照） ====================
CREATE TABLE dev_metric (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id),
    period_key  VARCHAR(16) NOT NULL,   -- 例如 2026-08 或 2026-W32
    payload     JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, period_key)
);
CREATE INDEX idx_dev_metric_proj ON dev_metric (project_id, period_key);