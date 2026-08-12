-- V20: 通用 key-value 元数据表（ClickHouse 回填游标等）

CREATE TABLE IF NOT EXISTS meta (
    meta_key    VARCHAR(128) PRIMARY KEY,
    meta_value  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);