-- ==================== UI 测试 (对标 MeterSphere UI 自动化，基于 Selenium 低代码) ====================

-- UI 测试用例表：记录浏览器自动化用例（低代码步骤 + 可选脚本）
CREATE TABLE IF NOT EXISTS ui_test_case (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    base_url     VARCHAR(512),                       -- 被测页面地址（可被环境覆盖）
    steps_json   JSONB NOT NULL DEFAULT '[]',         -- [{type, selector, value, ...}]
    script       TEXT,                                -- 可选 Selenium 脚本（预留）
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/PASSED/FAILED/SKIPPED
    last_result  JSONB,                              -- 最近一次执行结果
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ui_test_proj ON ui_test_case(project_id);