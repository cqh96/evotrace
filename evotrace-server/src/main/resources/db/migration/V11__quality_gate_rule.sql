-- ==================== 质量门禁可配置化 (V11) ====================
-- 借鉴 SonarQube Quality Gate：把 QualityGateChecker 的硬编码检查项抽成可配置规则。
-- project_id 为 NULL 表示全局默认规则（对所有项目生效）；项目级规则覆盖同名全局规则。

CREATE TABLE quality_gate_rule (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT REFERENCES project(id),          -- NULL = 全局默认
    rule_key    VARCHAR(64) NOT NULL,                    -- BUGS/FAILED_TESTS/BREAKING_CHANGES/TEST_COVERAGE/RISK_SCORE
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    weight      INT NOT NULL DEFAULT 10,                 -- 权重(总分100内分配)
    threshold   JSONB NOT NULL DEFAULT '{}',             -- 阈值，如 {"max":0} / {"min":60}
    params      JSONB NOT NULL DEFAULT '{}',             -- 扩展参数
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, rule_key)
);
CREATE INDEX idx_qgr_project ON quality_gate_rule (project_id);

-- 全局默认规则（映射原 QualityGateChecker 5 项硬编码检查及其权重/阈值）
INSERT INTO quality_gate_rule (project_id, rule_key, name, description, enabled, weight, threshold) VALUES
    (NULL, 'BUGS',             '高危缺陷门禁', '项目存在未关闭的 P0/P1 缺陷时阻断发布',   true, 30, '{"max":0}'),
    (NULL, 'FAILED_TESTS',     '失败用例门禁', '测试计划/发布窗口内存在失败用例时阻断发布', true, 25, '{"max":0}'),
    (NULL, 'BREAKING_CHANGES', '破坏性变更门禁', '存在未确认的破坏性变更时阻断发布',        true, 20, '{"max":0}'),
    (NULL, 'TEST_COVERAGE',    '测试覆盖率门禁', '近14天变更文件测试覆盖率需达到阈值',     true, 15, '{"min":60}'),
    (NULL, 'RISK_SCORE',       '风险评分门禁',  '发布风险评估分需在阈值内',               true, 10, '{"max":60}');