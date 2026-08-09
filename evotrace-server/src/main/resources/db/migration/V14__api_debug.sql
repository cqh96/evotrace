-- ==================== API 调试与管理 (V14) ====================
-- 对标 Apifox / Postman：接口清单、调试转发、Mock、用例、环境。
-- 资料来源：SDK 清单同步（INVENTORY）、导入 OpenAPI/Swagger/Postman/cURL/Apifox、手工。

-- 1) application.base_url：API 调试转发目标地址
ALTER TABLE application ADD COLUMN base_url VARCHAR(255);

-- 2) api_endpoint：接口清单
CREATE TABLE api_endpoint (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT NOT NULL REFERENCES project(id),
    app_id               BIGINT REFERENCES application(id),
    method               VARCHAR(8)  NOT NULL,
    path                 VARCHAR(512) NOT NULL,
    name                 VARCHAR(255),
    summary              TEXT,
    tags_json            JSONB NOT NULL DEFAULT '[]',
    params_json          JSONB NOT NULL DEFAULT '[]',   -- [{name,in,required,type,desc}]
    request_body_json    JSONB,                          -- 请求体 JSON Schema
    response_schema_json JSONB,                          -- 响应 JSON Schema
    mock_response_json   JSONB,                          -- mock 数据
    source               VARCHAR(32) DEFAULT 'INVENTORY',-- INVENTORY/IMPORT/MANUAL
    fingerprint          VARCHAR(64),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, app_id, method, path)
);
CREATE INDEX idx_endpoint_project ON api_endpoint (project_id);

-- 3) api_environment：调试环境（base-url + 全局请求头）
CREATE TABLE api_environment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    name         VARCHAR(255) NOT NULL,
    base_url     VARCHAR(255),
    headers_json JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_env_project ON api_environment (project_id);

-- 4) api_test_case：保存的调试用例
CREATE TABLE api_test_case (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    endpoint_id      BIGINT REFERENCES api_endpoint(id),
    name             VARCHAR(255) NOT NULL,
    request_json     JSONB,        -- {method,path,headers,query,body}
    response_json    JSONB,        -- 最近一次响应
    expected_status  INT,
    last_status      INT,
    last_duration_ms INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_testcase_project ON api_test_case (project_id);