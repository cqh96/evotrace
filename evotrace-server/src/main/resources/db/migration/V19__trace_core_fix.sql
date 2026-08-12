-- ==================== Trace Core / Phase A 修正 (V19) ====================
-- 对应 docs/10-链路增强与补齐方案.md §8.2.2（P0-2 修订）
-- 修正 V18 种子规则 REQ 正则：\d{3,} → \d+，与自动生成键 REQ-{id} 一致
-- 注：change_event.commit_message 列已由 V6 提供，无需重复新增 message 列。

UPDATE project_link_rule
SET pattern    = '(?i)\b(?<reqKey>REQ[-_]?\d+)\b',
    updated_at = now()
WHERE name = 'REQ key'
  AND pattern LIKE '%REQ[-_]?\d{3,}%';