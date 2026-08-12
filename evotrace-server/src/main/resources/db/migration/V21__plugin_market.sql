-- V21: 解析器插件市场（V2.5）
-- 无多租户，插件为全局/按项目维度安装。

-- 插件市场目录
CREATE TABLE IF NOT EXISTS plugin_catalog (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id     VARCHAR(128) NOT NULL UNIQUE,          -- 如 vendor.parser.git-java
    name          VARCHAR(128) NOT NULL,
    category      VARCHAR(32)  NOT NULL,                 -- CODE / API / DDL / CONFIG / DEPENDENCY
    description   TEXT,
    author        VARCHAR(128),
    compat_range  VARCHAR(128),                          -- 兼容的 evotrace 版本区间
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 插件版本发布
CREATE TABLE IF NOT EXISTS plugin_release (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id     VARCHAR(128) NOT NULL REFERENCES plugin_catalog(plugin_id),
    version       VARCHAR(32)  NOT NULL,
    jar_ref       VARCHAR(512) NOT NULL,                 -- 插件 Jar 的存储引用
    sha256        VARCHAR(64)  NOT NULL,                 -- 校验和
    signature     TEXT,                                  -- 签名
    min_version   VARCHAR(32),
    max_version   VARCHAR(32),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plugin_id, version)
);

-- 插件安装记录
CREATE TABLE IF NOT EXISTS plugin_install (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plugin_id     VARCHAR(128) NOT NULL UNIQUE REFERENCES plugin_catalog(plugin_id),
    version       VARCHAR(32)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    installed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);