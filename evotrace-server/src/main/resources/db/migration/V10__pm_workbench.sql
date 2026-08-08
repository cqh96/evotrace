-- ==================== PM 需求工作台 ====================
-- 需求结构化建模字段（设计需求）
ALTER TABLE requirement
    ADD COLUMN business_value      TEXT,          -- 业务价值
    ADD COLUMN user_story          TEXT,          -- 用户故事
    ADD COLUMN acceptance_criteria TEXT,          -- 验收标准 (markdown)
    ADD COLUMN estimate_days       NUMERIC(5,1),  -- 预估工时(天)
    ADD COLUMN tech_lead           VARCHAR(64);   -- 技术负责人

-- 需求文档（写文档：每次保存生成新版本号，版本不可变可追溯）
CREATE TABLE requirement_document (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requirement_id BIGINT NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
    version        INT NOT NULL,
    title          VARCHAR(256) NOT NULL DEFAULT 'PRD',
    content        TEXT NOT NULL,                 -- Markdown 原文
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (requirement_id, version)
);
CREATE INDEX idx_req_doc ON requirement_document (requirement_id, version DESC);

-- 原型（写原型：每需求一行最新状态，pages 为完整 JSONB）
CREATE TABLE requirement_prototype (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requirement_id BIGINT NOT NULL UNIQUE REFERENCES requirement(id) ON DELETE CASCADE,
    pages          JSONB NOT NULL DEFAULT '[]',
    updated_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 需求子任务（分配需求任务）
CREATE TABLE requirement_task (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requirement_id BIGINT NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
    title          VARCHAR(512) NOT NULL,
    assignee       VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'TODO',   -- TODO/DOING/DONE
    estimate_hours NUMERIC(6,1),
    priority       VARCHAR(8) NOT NULL DEFAULT 'P2',      -- P0-P3
    sort_order     INT NOT NULL DEFAULT 0,
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_req_task ON requirement_task (requirement_id, sort_order);

-- 状态流转审计（生命周期：开区间模型——进入时开一行，离开时回填 left_at；NULL = 当前驻留）
CREATE TABLE requirement_status_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requirement_id BIGINT NOT NULL REFERENCES requirement(id) ON DELETE CASCADE,
    status         VARCHAR(32) NOT NULL,
    from_status    VARCHAR(32),
    actor          VARCHAR(64),
    entered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at        TIMESTAMPTZ
);
CREATE INDEX idx_req_status_hist ON requirement_status_history (requirement_id, entered_at);

-- 存量需求回填初始历史（以 created_at 为进入时间，保证生命周期统计覆盖历史数据）
INSERT INTO requirement_status_history (requirement_id, status, actor, entered_at)
SELECT id, status, 'SEED', created_at FROM requirement ON CONFLICT DO NOTHING;
