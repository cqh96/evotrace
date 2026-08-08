-- ==================== AI 审查回写 / PR 描述 (V12) ====================
-- 借鉴 PR-Agent：审查结果可回写 Git 平台；单 MR 描述生成落库到 ai_semantic_unit。

-- 审查回写状态：记录该 review 是否已推回 Git 平台及回写位置
ALTER TABLE ai_code_review
    ADD COLUMN pushed_back BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN push_back_url VARCHAR(512),
    ADD COLUMN pushed_back_at TIMESTAMPTZ;

-- 单 MR 描述 kind（PR_DESCRIPTION）已复用于 ai_semantic_unit，无需新增表。