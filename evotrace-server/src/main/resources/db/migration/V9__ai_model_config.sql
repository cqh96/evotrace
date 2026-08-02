-- EvoTrace AI 模型接入配置 (V9)
-- 系统页面维护的 OpenAI 兼容模型配置,运行时由 ModelRouter 动态加载

CREATE TABLE ai_model_config (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(64) NOT NULL UNIQUE,               -- 配置名(如 "主模型-方舟")
    provider    VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',     -- OPENAI / ARK / DEEPSEEK / CUSTOM(仅元信息,协议均为 OpenAI 兼容)
    base_url    VARCHAR(256) NOT NULL,                     -- OpenAI 兼容端点
    api_key     VARCHAR(256),                              -- 可空(无需 key 的网关)
    model_name  VARCHAR(128) NOT NULL,
    temperature NUMERIC(3,2) DEFAULT 0.20,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    is_default  BOOLEAN NOT NULL DEFAULT false,            -- 默认路由目标,同一时刻至多一条
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_model_config_active ON ai_model_config (enabled, is_default);
