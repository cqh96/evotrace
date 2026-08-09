-- ================================================================
-- 飞书多维表格(Bitable) 同步：缺陷 + 测试用例 双向同步
-- 与 Jira 同步并存，按项目独立启用（pluggable source，不破坏现有 Jira）
-- ================================================================

-- 1) 项目级飞书同步配置
CREATE TABLE project_feishu_config (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL UNIQUE REFERENCES project(id),
    app_id           VARCHAR(128),            -- 飞书自建应用 App ID
    app_secret       VARCHAR(256),            -- 飞书自建应用 App Secret（只写，不回显）
    app_token        VARCHAR(128),            -- 多维表格(Bitable) app_token
    bug_table_id     VARCHAR(128),            -- 缺陷所在表格 table_id
    case_table_id    VARCHAR(128),            -- 测试用例所在表格 table_id
    field_map        JSONB NOT NULL DEFAULT '{}',  -- EvoTrace语义字段 → Bitable列名，如 {"title":"标题","description":"描述","severity":"优先级","status":"状态","testType":"用例类型"}
    status_map       JSONB NOT NULL DEFAULT '{}',  -- {"OPEN":"待处理","IN_PROGRESS":"处理中","FIXED":"已修复","VERIFIED":"已验证","CLOSED":"已关闭","REOPENED":"重新打开"}
    enabled          BOOLEAN NOT NULL DEFAULT false,
    last_sync_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2) test_case 增加同步溯源列（与 bug_ticket.source/external_key 对齐）
ALTER TABLE test_case ADD COLUMN source VARCHAR(32) DEFAULT 'MANUAL';
ALTER TABLE test_case ADD COLUMN external_key VARCHAR(128);
CREATE UNIQUE INDEX idx_tc_external ON test_case (project_id, source, external_key)
    WHERE external_key IS NOT NULL;