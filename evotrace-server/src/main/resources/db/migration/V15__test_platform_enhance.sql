-- ==================== 测试平台完善 (V15) ====================
-- 对标 MeterSphere：用例增强 / 场景编排 / 报告 / 环境 / 调度 / 性能 / CI

-- 1) test_case：自定义字段
ALTER TABLE test_case ADD COLUMN IF NOT EXISTS custom_fields JSONB NOT NULL DEFAULT '{}';

-- 2) 用例版本快照表
CREATE TABLE IF NOT EXISTS test_case_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    test_case_id  BIGINT    NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    version       INT       NOT NULL,
    title         VARCHAR(255),
    description   TEXT,
    steps         TEXT,
    test_type     VARCHAR(32),
    priority      VARCHAR(8),
    related_files TEXT,
    related_apis  TEXT,
    tags          TEXT,
    custom_fields JSONB     NOT NULL DEFAULT '{}',
    changed_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (test_case_id, version)
);

-- 3) 接口场景（多接口编排，对标 MeterSphere 场景自动化）
CREATE TABLE IF NOT EXISTS api_scenario (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    chart_json   JSONB,                          -- 场景 DAG 示意（预留）
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS api_scenario_step (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scenario_id BIGINT NOT NULL REFERENCES api_scenario(id) ON DELETE CASCADE,
    sort_order  INT    NOT NULL DEFAULT 0,
    step_type   VARCHAR(16) NOT NULL,            -- HTTP / EXTRACT / ASSERT / IF
    name        VARCHAR(255),
    config_json JSONB NOT NULL DEFAULT '{}'
);

-- 4) 计划项支持场景类型
ALTER TABLE test_plan_item ADD COLUMN IF NOT EXISTS item_type VARCHAR(8) NOT NULL DEFAULT 'CASE';
ALTER TABLE test_plan_item ADD COLUMN IF NOT EXISTS scenario_id BIGINT REFERENCES api_scenario(id);

-- 5) 测试计划绑定执行环境
ALTER TABLE test_plan ADD COLUMN IF NOT EXISTS environment_id BIGINT REFERENCES api_environment(id);

-- 6) 环境支持全局变量
ALTER TABLE api_environment ADD COLUMN IF NOT EXISTS variables JSONB NOT NULL DEFAULT '{}';

-- 7) 测试报告（可视化 + 分享）
CREATE TABLE IF NOT EXISTS test_report (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES project(id),
    plan_id       BIGINT REFERENCES test_plan(id),
    name          VARCHAR(255) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'DONE',
    summary_json  JSONB NOT NULL,
    share_token   VARCHAR(64),
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 8) 定时调度
CREATE TABLE IF NOT EXISTS test_schedule (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id),
    plan_id     BIGINT NOT NULL REFERENCES test_plan(id),
    name        VARCHAR(255) NOT NULL,
    cron        VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMPTZ,
    created_by  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 9) 性能测试（轻量单机压测入口；JMeter .jmx 上传解析预留）
CREATE TABLE IF NOT EXISTS performance_test (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    name         VARCHAR(255) NOT NULL,
    endpoint_id  BIGINT REFERENCES api_endpoint(id),
    concurrency  INT    NOT NULL DEFAULT 10,
    duration_sec INT    NOT NULL DEFAULT 30,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/RUNNING/DONE/FAILED
    summary_json JSONB,
    report_json  JSONB,
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_case_version_case ON test_case_version(test_case_id);
CREATE INDEX IF NOT EXISTS idx_scenario_proj ON api_scenario(project_id);
CREATE INDEX IF NOT EXISTS idx_scenario_step_scn ON api_scenario_step(scenario_id);
CREATE INDEX IF NOT EXISTS idx_report_proj ON test_report(project_id);
CREATE INDEX IF NOT EXISTS idx_schedule_proj ON test_schedule(project_id);
CREATE INDEX IF NOT EXISTS idx_perf_proj ON performance_test(project_id);

-- 10) CI/CD 触发任务（绑定测试计划，供 Jenkins/GitHub Actions 等触发）
CREATE TABLE IF NOT EXISTS ci_trigger (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    plan_id      BIGINT NOT NULL REFERENCES test_plan(id),
    name         VARCHAR(255) NOT NULL,
    trigger_type VARCHAR(16)  NOT NULL DEFAULT 'WEBHOOK', -- WEBHOOK / CRON / MR
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ci_trigger_proj ON ci_trigger(project_id);