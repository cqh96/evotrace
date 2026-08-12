-- ==================== Trace Core / Phase A (V18) ====================
-- 对应 docs/10-链路增强与补齐方案.md §8.2 / 任务 A-DB-1
-- 目标：req_key + 关联规则 + artifact_link 边表 + 审计/忽略 + 存量回填

-- ---------- 8.2.1 requirement.req_key ----------
ALTER TABLE requirement
    ADD COLUMN IF NOT EXISTS req_key VARCHAR(64);

-- 回填 1：external_key 形如 ABC-123 / ABC_123 时优先用作业务键（项目内取最小 id，避免唯一冲突）
WITH candidates AS (
    SELECT id,
           project_id,
           upper(replace(external_key, '_', '-')) AS k,
           row_number() OVER (
               PARTITION BY project_id, upper(replace(external_key, '_', '-'))
               ORDER BY id
           ) AS rn
    FROM requirement
    WHERE req_key IS NULL
      AND external_key IS NOT NULL
      AND external_key ~* '^[A-Za-z][A-Za-z0-9]*[-_]\d+$'
)
UPDATE requirement r
SET req_key = c.k,
    updated_at = now()
FROM candidates c
WHERE r.id = c.id
  AND c.rn = 1
  AND r.req_key IS NULL;

-- 回填 2：其余需求统一 REQ-{id}
UPDATE requirement
SET req_key = 'REQ-' || id,
    updated_at = now()
WHERE req_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_requirement_project_req_key
    ON requirement (project_id, req_key)
    WHERE req_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_requirement_req_key
    ON requirement (project_id, lower(req_key));

-- ---------- 8.2.3 project_trace_setting ----------
CREATE TABLE project_trace_setting (
    project_id           BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    req_key_prefix       VARCHAR(32) NOT NULL DEFAULT 'REQ',
    auto_link_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    hash_issue_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    branch_template      VARCHAR(256) DEFAULT 'feature/{reqKey}-*',
    commit_template      VARCHAR(256) DEFAULT '{reqKey} <summary>',
    ai_suggest_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO project_trace_setting (project_id)
SELECT id FROM project
ON CONFLICT (project_id) DO NOTHING;

-- ---------- 8.2.2 project_link_rule ----------
CREATE TABLE project_link_rule (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name            VARCHAR(128) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    priority        INT NOT NULL DEFAULT 100,
    pattern         VARCHAR(512) NOT NULL,
    extract_group   VARCHAR(64) NOT NULL DEFAULT 'reqKey',
    apply_to        VARCHAR(64) NOT NULL DEFAULT 'COMMIT_MESSAGE',
    -- COMMIT_MESSAGE | MR_TITLE | MR_BODY | BRANCH_NAME
    link_type       VARCHAR(32) NOT NULL DEFAULT 'IMPLEMENTS',
    confidence      INT NOT NULL DEFAULT 90,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_link_rule_project
    ON project_link_rule (project_id, enabled, priority);

-- 为每个现有项目写入默认规则（同一 pattern 按 apply_to 拆多行）
INSERT INTO project_link_rule (project_id, name, enabled, priority, pattern, extract_group, apply_to, link_type, confidence)
SELECT p.id, v.name, v.enabled, v.priority, v.pattern, v.extract_group, v.apply_to, v.link_type, v.confidence
FROM project p
CROSS JOIN (VALUES
    ('REQ key',       TRUE,  10, '(?i)\b(?<reqKey>REQ[-_]?\d{3,})\b',              'reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 95),
    ('REQ key',       TRUE,  10, '(?i)\b(?<reqKey>REQ[-_]?\d{3,})\b',              'reqKey', 'MR_TITLE',       'IMPLEMENTS', 95),
    ('REQ key',       TRUE,  10, '(?i)\b(?<reqKey>REQ[-_]?\d{3,})\b',              'reqKey', 'BRANCH_NAME',    'IMPLEMENTS', 95),
    ('JIRA/Issue key',TRUE,  20, '(?i)\b(?<reqKey>[A-Z][A-Z0-9]+-\d+)\b',          'reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 90),
    ('JIRA/Issue key',TRUE,  20, '(?i)\b(?<reqKey>[A-Z][A-Z0-9]+-\d+)\b',          'reqKey', 'MR_TITLE',       'IMPLEMENTS', 90),
    -- hash issue 默认关闭，需项目打开 hash_issue_enabled 后再启用
    ('Hash issue',    FALSE, 30, '#(?<reqKey>\d{3,})\b',                           'reqKey', 'COMMIT_MESSAGE', 'IMPLEMENTS', 70)
) AS v(name, enabled, priority, pattern, extract_group, apply_to, link_type, confidence)
WHERE NOT EXISTS (
    SELECT 1 FROM project_link_rule r
    WHERE r.project_id = p.id AND r.name = v.name AND r.apply_to = v.apply_to
);

-- ---------- 8.2.4 artifact_link ----------
CREATE TABLE artifact_link (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    from_type       VARCHAR(32) NOT NULL,
    from_id         VARCHAR(128) NOT NULL,
    to_type         VARCHAR(32) NOT NULL,
    to_id           VARCHAR(128) NOT NULL,
    link_type       VARCHAR(32) NOT NULL,
    confidence      INT NOT NULL DEFAULT 100,
    source          VARCHAR(32) NOT NULL,
    -- AUTO_COMMIT_KEY | AUTO_MR | AUTO_BRANCH | AUTO_AI | MANUAL | IMPORT | MIGRATION | AUTO_TIME_WINDOW
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | PENDING | REJECTED | IGNORED
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    meta            JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE UNIQUE INDEX uk_artifact_link_active
    ON artifact_link (project_id, from_type, from_id, to_type, to_id, link_type)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_artifact_link_from
    ON artifact_link (project_id, from_type, from_id, link_type);

CREATE INDEX idx_artifact_link_to
    ON artifact_link (project_id, to_type, to_id, link_type);

CREATE INDEX idx_artifact_link_status
    ON artifact_link (project_id, status, confidence);

-- 存量：需求 → 迭代 BELONGS_TO
INSERT INTO artifact_link (
    project_id, from_type, from_id, to_type, to_id,
    link_type, confidence, source, status, created_by, meta
)
SELECT r.project_id,
       'REQUIREMENT', r.id::text,
       'ITERATION',   r.iteration_id::text,
       'BELONGS_TO', 100, 'MIGRATION', 'ACTIVE', 'FLYWAY',
       jsonb_build_object('migratedFrom', 'requirement.iteration_id')
FROM requirement r
WHERE r.iteration_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM artifact_link al
      WHERE al.project_id = r.project_id
        AND al.from_type = 'REQUIREMENT' AND al.from_id = r.id::text
        AND al.to_type = 'ITERATION' AND al.to_id = r.iteration_id::text
        AND al.link_type = 'BELONGS_TO'
        AND al.status = 'ACTIVE'
  );

-- 存量：变更 → 迭代 BELONGS_TO（兼容 change_event.iteration_id）
INSERT INTO artifact_link (
    project_id, from_type, from_id, to_type, to_id,
    link_type, confidence, source, status, created_by, meta
)
SELECT c.project_id,
       'CHANGE_EVENT', c.event_id,
       'ITERATION',    c.iteration_id::text,
       'BELONGS_TO', 100, 'MIGRATION', 'ACTIVE', 'FLYWAY',
       jsonb_build_object('migratedFrom', 'change_event.iteration_id')
FROM change_event c
WHERE c.iteration_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM artifact_link al
      WHERE al.project_id = c.project_id
        AND al.from_type = 'CHANGE_EVENT' AND al.from_id = c.event_id
        AND al.to_type = 'ITERATION' AND al.to_id = c.iteration_id::text
        AND al.link_type = 'BELONGS_TO'
        AND al.status = 'ACTIVE'
  );

-- ---------- 8.2.5 trace_link_audit ----------
CREATE TABLE trace_link_audit (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    link_id         BIGINT,
    action          VARCHAR(32) NOT NULL,
    -- CREATE | CONFIRM | REJECT | IGNORE | DELETE | REBUILD
    actor           VARCHAR(64),
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trace_link_audit_project
    ON trace_link_audit (project_id, created_at DESC);

-- ---------- orphan ignore（未关联提交忽略清单） ----------
CREATE TABLE trace_orphan_ignore (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    change_event_id  VARCHAR(64) NOT NULL,
    reason           VARCHAR(512),
    actor            VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, change_event_id)
);

CREATE INDEX idx_trace_orphan_ignore_project
    ON trace_orphan_ignore (project_id, created_at DESC);
