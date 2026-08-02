-- EvoTrace test management (MeterSphere-inspired, V8)
-- 测试用例模块树 + 测试计划 + 用例↔缺陷关联 + Jira 同步配置

-- 1) test_case 模块树（自引用 parent_id + 节点类型 MODULE/CASE）
ALTER TABLE test_case ADD COLUMN parent_id BIGINT REFERENCES test_case(id) ON DELETE RESTRICT;
ALTER TABLE test_case ADD COLUMN node_type VARCHAR(16) NOT NULL DEFAULT 'CASE';
CREATE INDEX idx_tc_parent ON test_case (project_id, parent_id);

-- 2) 测试计划（编排 → 执行 → 报告）
CREATE TABLE test_plan (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES project(id),
    name           VARCHAR(256) NOT NULL,
    description    TEXT,
    target_version VARCHAR(64),
    from_version   VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',   -- DRAFT / RUNNING / DONE
    executor       VARCHAR(64),
    created_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tp_project ON test_plan (project_id, status);

-- 3) 计划项：计划 ↔ 用例 ↔ 执行结果（单一事实源）
CREATE TABLE test_plan_item (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id       BIGINT NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    test_case_id  BIGINT NOT NULL REFERENCES test_case(id),
    sort_order    INT NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING/PASSED/FAILED/BLOCKED/SKIPPED
    executor      VARCHAR(64),
    result_detail TEXT,                                    -- JSON {actual, screenshots, logs}
    executed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plan_id, test_case_id)
);
CREATE INDEX idx_tpi_plan ON test_plan_item (plan_id, status);
CREATE INDEX idx_tpi_executed ON test_plan_item (executed_at);

-- 4) 用例 ↔ 缺陷关联
CREATE TABLE test_case_bug_link (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    test_case_id BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    bug_id       BIGINT NOT NULL REFERENCES bug_ticket(id) ON DELETE CASCADE,
    link_type    VARCHAR(16) NOT NULL DEFAULT 'RELATED',   -- RELATED / FAILED_CAUSE
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (test_case_id, bug_id)
);
CREATE INDEX idx_tcbl_bug ON test_case_bug_link (bug_id);

-- 5) Jira 同步配置（项目级；TAPD 无公开 REST API，预留同结构后续扩展）
CREATE TABLE project_jira_config (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL UNIQUE REFERENCES project(id),
    base_url         VARCHAR(512),
    username         VARCHAR(128),
    api_token        VARCHAR(512),
    jira_project_key VARCHAR(64),
    issue_type       VARCHAR(64) NOT NULL DEFAULT 'Bug',
    status_map       JSONB NOT NULL DEFAULT '{}',   -- {"OPEN":"To Do","IN_PROGRESS":"In Progress","FIXED":"Resolved","VERIFIED":"Resolved","CLOSED":"Closed","REOPENED":"Reopened"}
    enabled          BOOLEAN NOT NULL DEFAULT false,
    last_sync_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 6) 非计划执行趋势聚合索引
CREATE INDEX idx_te_executed ON test_execution (executed_at);
