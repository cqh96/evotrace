-- V23: 需求文档智能解析（AI 需求分析 → 需求看板 + 测试用例）

-- 原始材料留档：链接/上传文档统一登记，原文外置 blob
CREATE TABLE IF NOT EXISTS requirement_source_doc (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id),
    source_type  VARCHAR(20)  NOT NULL,              -- LINK / FILE / PROTOTYPE_PRD
    file_name    VARCHAR(512),
    external_url VARCHAR(1024),
    blob_ref     VARCHAR(128),                       -- 原文 blob（BlobStoreService）
    char_count   INTEGER,
    parse_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PARSED / FAILED
    model        VARCHAR(128),
    parse_result JSONB,                              -- AI 结构化输出留档（复查用）
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_requirement_source_doc_project
    ON requirement_source_doc(project_id, created_at DESC);

-- 需求可回溯到出处文档；source 语义扩展 AI_DOC（VARCHAR 无约束，仅约定）
ALTER TABLE requirement
    ADD COLUMN IF NOT EXISTS source_doc_id BIGINT REFERENCES requirement_source_doc(id);

-- AI 生成用例标记
ALTER TABLE test_case
    ADD COLUMN IF NOT EXISTS ai_generated BOOLEAN NOT NULL DEFAULT false;
