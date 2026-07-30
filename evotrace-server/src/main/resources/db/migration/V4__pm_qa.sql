-- EvoTrace PM-QA-Ops 全链路打通 (V4)
-- 需求管理 → 测试用例 → 缺陷追踪 → 质量门禁 → 端到端追溯

-- ==================== 需求增强 ====================
CREATE TABLE requirement (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    workspace_id    BIGINT NOT NULL REFERENCES workspace(id),
    title           VARCHAR(512) NOT NULL,
    description     TEXT,
    priority        VARCHAR(16) NOT NULL DEFAULT 'P2',       -- P0/P1/P2/P3
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT',    -- DRAFT/REVIEW/DEVELOPING/TESTING/DONE
    source          VARCHAR(32) NOT NULL DEFAULT 'MANUAL',   -- MANUAL/JIRA/FEISHU/TAPD
    external_key    VARCHAR(128),
    external_url    VARCHAR(512),
    prototype_url   VARCHAR(512),       -- 原型链接 (Figma/蓝湖/Axure)
    design_url      VARCHAR(512),       -- 设计稿链接
    product_manager VARCHAR(64),        -- 产品经理
    assigned_to     VARCHAR(64),        -- 负责人
    iteration_id    BIGINT REFERENCES iteration(id),
    target_version  VARCHAR(64),        -- 目标发布版本
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_req_project ON requirement (project_id);
CREATE INDEX idx_req_status ON requirement (project_id, status);

-- ==================== 测试用例 ====================
CREATE TABLE test_case (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    requirement_id  BIGINT REFERENCES requirement(id),
    title           VARCHAR(512) NOT NULL,
    description     TEXT,
    steps           TEXT,              -- JSON: [{step, expected}]
    test_type       VARCHAR(32) NOT NULL DEFAULT 'FUNCTIONAL', -- FUNCTIONAL/REGRESSION/PERF/SECURITY/API
    priority        VARCHAR(8) NOT NULL DEFAULT 'P2',         -- P0/P1/P2/P3
    related_files   TEXT,              -- 关联文件路径 (逗号分隔)
    related_apis    TEXT,              -- 关联接口 (逗号分隔)
    tags            VARCHAR(256),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tc_project ON test_case (project_id);
CREATE INDEX idx_tc_req ON test_case (requirement_id);

-- ==================== 测试执行记录 ====================
CREATE TABLE test_execution (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    test_case_id    BIGINT NOT NULL REFERENCES test_case(id),
    release_id      BIGINT REFERENCES release(id),
    executor        VARCHAR(64),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/PASSED/FAILED/BLOCKED/SKIPPED
    result_detail   TEXT,               -- JSON: {actual, screenshots, logs}
    executed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_te_release ON test_execution (release_id);

-- ==================== 缺陷/Bug ====================
CREATE TABLE bug_ticket (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    requirement_id  BIGINT REFERENCES requirement(id),
    title           VARCHAR(512) NOT NULL,
    description     TEXT,
    severity        VARCHAR(16) NOT NULL DEFAULT 'P2',     -- P0(阻塞)/P1(严重)/P2(一般)/P3(轻微)
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',    -- OPEN/IN_PROGRESS/FIXED/VERIFIED/CLOSED/REOPENED
    source          VARCHAR(32) DEFAULT 'MANUAL',           -- MANUAL/JIRA/TAPD
    external_key    VARCHAR(128),
    found_by        VARCHAR(64),        -- 发现人(QA)
    found_version   VARCHAR(64),        -- 发现版本
    fixed_version   VARCHAR(64),        -- 修复版本
    assigned_to     VARCHAR(64),        -- 修复人
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bug_project ON bug_ticket (project_id);
CREATE INDEX idx_bug_status ON bug_ticket (project_id, status);

-- Bug ↔ ChangeEvent 关联追溯
CREATE TABLE bug_change_link (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bug_id          BIGINT NOT NULL REFERENCES bug_ticket(id),
    change_event_id VARCHAR(64) NOT NULL,
    link_type       VARCHAR(32) NOT NULL DEFAULT 'FIX',    -- FIX(修复)/INTRODUCE(引入)/RELATED(相关)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bug_id, change_event_id, link_type)
);

-- ==================== 质量门禁 ====================
CREATE TABLE quality_gate (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    release_id       BIGINT REFERENCES release(id),
    target_version   VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/PASSED/FAILED
    check_results    JSONB NOT NULL DEFAULT '{}',  -- 各检查项结果
    checked_at       TIMESTAMPTZ,
    checked_by       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ==================== 端到端追溯视图 (物化) ====================
CREATE TABLE e2e_trace (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    requirement_id   BIGINT REFERENCES requirement(id),
    iteration_id     BIGINT REFERENCES iteration(id),
    change_event_id  VARCHAR(64),
    test_case_id     BIGINT REFERENCES test_case(id),
    bug_ticket_id    BIGINT REFERENCES bug_ticket(id),
    release_id       BIGINT REFERENCES release(id),
    trace_path       JSONB NOT NULL DEFAULT '[]',  -- 有序的节点链 [{type, id, title, time}]
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_e2e_project ON e2e_trace (project_id);
CREATE INDEX idx_e2e_req ON e2e_trace (requirement_id);

-- ==================== PM变更通知 ====================
CREATE TABLE pm_qa_notification (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    trigger_event   VARCHAR(32) NOT NULL,           -- REQUIREMENT_CHANGED / API_BROKEN / TEST_FAILED / BUG_FOUND
    target_role     VARCHAR(16) NOT NULL,           -- PM / QA / ALL
    target_user     VARCHAR(64),
    title           VARCHAR(256) NOT NULL,
    content         TEXT,
    read            BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_project ON pm_qa_notification (project_id, target_role, read);
