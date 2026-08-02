-- EvoTrace AI Code Review Engine (V5)
-- 检测 AI 生成代码 → 审查逻辑/风险 → 输出结构化报告

CREATE TABLE ai_code_review (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    change_event_id  VARCHAR(64) NOT NULL,
    review_type      VARCHAR(32) NOT NULL DEFAULT 'AUTO',      -- AUTO(自动) / MANUAL(人工触发) / PR(PR触发)
    ai_generated     BOOLEAN NOT NULL DEFAULT false,            -- 是否AI生成的代码
    ai_source        VARCHAR(64),                               -- cursor/copilot/claude/codebuddy/unknown
    diff_summary     TEXT,                                      -- AI逻辑变更摘要
    logic_analysis   TEXT,                                      -- 逻辑分析说明
    overall_score    INT NOT NULL DEFAULT 0,                    -- 0-100 综合评分
    overall_verdict  VARCHAR(32) NOT NULL DEFAULT 'PASS',       -- PASS / WARNING / FAIL
    suggestion       TEXT,                                      -- 改进建议
    reviewed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_event ON ai_code_review (change_event_id);
CREATE INDEX idx_review_project ON ai_code_review (project_id, reviewed_at DESC);

-- Review findings (每条具体的风险/问题/建议)
CREATE TABLE review_finding (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id        BIGINT NOT NULL REFERENCES ai_code_review(id) ON DELETE CASCADE,
    severity         VARCHAR(16) NOT NULL DEFAULT 'INFO',       -- CRITICAL / WARNING / INFO / SUGGESTION
    category         VARCHAR(32) NOT NULL,                      -- BUG / SECURITY / PERFORMANCE / LOGIC / STYLE / DEPENDENCY
    file_path        VARCHAR(1024),
    line_range       VARCHAR(64),                               -- "45-52" or "L45"
    title            VARCHAR(256) NOT NULL,
    description      TEXT,
    code_snippet     TEXT,                                      -- 问题代码片段
    suggestion       TEXT,                                      -- 修复建议
    auto_fixable     BOOLEAN NOT NULL DEFAULT false,            -- 是否可自动修复
    acknowledged     BOOLEAN NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_review ON review_finding (review_id);
CREATE INDEX idx_finding_severity ON review_finding (severity);

-- AI 提交统计（按作者/AI来源聚合）
CREATE TABLE ai_commit_stats (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    stat_date        DATE NOT NULL,
    ai_source        VARCHAR(64) NOT NULL,
    commit_count     INT NOT NULL DEFAULT 0,
    total_lines      INT NOT NULL DEFAULT 0,
    review_pass_rate NUMERIC(5,2),                              -- 审查通过率
    avg_score        NUMERIC(5,2),                              -- 平均评分
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, stat_date, ai_source)
);
