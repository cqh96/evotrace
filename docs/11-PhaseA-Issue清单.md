# Phase A Issue 清单（Trace Core）

> 来源：[10-链路增强与补齐方案.md](10-链路增强与补齐方案.md) §8  
> 用法：可直接复制标题/正文到 GitHub Issues；或按 ID 本地跟踪。  
> 状态：`todo` / `doing` / `done`

---

## Epic

**标题**：`[Phase A] Trace Core — 需求↔代码链路打底`

**正文**：

```markdown
## Summary
打通需求↔代码结构化关联：req_key、关联规则、artifact_link、关联治理中心、需求/版本全景、完整度评分。

## Spec
- docs/10-链路增强与补齐方案.md §8
- 验收剧本：§8.7 A1–A9

## Children
见本仓库 docs/11-PhaseA-Issue清单.md
```

---

## Issue 列表

| ID | 状态 | 标题 | 依赖 | 预估 |
| --- | --- | --- | --- | --- |
| A-DB-1 | done | `[Phase A][DB] Flyway V18 Trace Core 表结构与回填` | — | 0.5d |
| A-DB-2 | todo | `[Phase A][DB] Flyway V19 P0 修订：change_event.message + REQ 规则正则` | — | 0.5d |
| A-BE-1 | todo | `[Phase A][BE] ReqKeyService：生成/校验/创建回写` | A-DB-1 | 0.5d |
| A-BE-2 | todo | `[Phase A][BE] ArtifactLinkService + trace_link_audit` | A-DB-1 | 1d |
| A-BE-3 | todo | `[Phase A][BE] LinkRuleEngine + Commit/MR 自动建边` | A-BE-1, A-BE-2, A-DB-2 | 1.5d |
| A-BE-4 | todo | `[Phase A][BE] Trace settings/rules API` | A-DB-1 | 0.5d |
| A-BE-5 | todo | `[Phase A][BE] Governance API（未关联/待确认/悬空键/断链）` | A-BE-2, A-BE-3, A-DB-2 | 1.5d |
| A-BE-6 | todo | `[Phase A][BE] CompletenessScorer + 需求/版本 Overview API` | A-BE-2 | 1.5d |
| A-BE-7 | todo | `[Phase A][BE] Release ChangeSet rebuild + e2e_trace 刷新` | A-BE-2, A-BE-6 | 1d |
| A-FE-1 | todo | `[Phase A][FE] TraceGovernanceView + 设置抽屉` | A-BE-4, A-BE-5 | 2d |
| A-FE-2 | todo | `[Phase A][FE] 需求全景 Tab + 完整度` | A-BE-6 | 1.5d |
| A-FE-3 | todo | `[Phase A][FE] ReleaseCockpitView + 路由入口` | A-BE-6, A-BE-7 | 1.5d |
| A-FE-4 | todo | `[Phase A][FE] TraceGraphPanel 简化版 + 看板微改` | A-FE-2 | 1d |
| A-QA-1 | todo | `[Phase A][QA] 验收 A1–A9 + 对接指南 commit 约定小节` | A-FE-* , A-BE-* | 1d |
| A-DOC-1 | todo | `[Phase A][DOC] 同步 03-API / 01 里程碑 / README` | A-BE-4..7 | 0.5d |

**建议并行波次**

1. **Wave 1**：A-DB-1 → A-BE-1 + A-BE-2 + A-BE-4；并行 A-DB-2（V19 修订）
2. **Wave 2**：A-BE-3 → A-BE-5；并行 A-BE-6
3. **Wave 3**：A-BE-7；并行 A-FE-1、A-FE-2
4. **Wave 4**：A-FE-3、A-FE-4 → A-QA-1、A-DOC-1  

---

## Issue 正文模板（可复制）

### A-DB-1 — Flyway V18（已完成）

```markdown
## Summary
落地 Trace Core 数据库：req_key、project_link_rule、project_trace_setting、artifact_link、trace_link_audit、trace_orphan_ignore；存量回填与默认规则种子。

## Deliverable
- [x] evotrace-server/src/main/resources/db/migration/V18__trace_core.sql

## Acceptance
- [ ] 本地/测试库 Flyway migrate 成功
- [ ] 现有 requirement 均有 req_key
- [ ] 每个 project 有 trace_setting 与默认 link_rule
- [ ] 存量 iteration 关联已双写为 BELONGS_TO 边
```

### A-DB-2 — Flyway V19（P0 修订）

```markdown
## Summary
落地 P0 修订：1) change_event 增加 message 列（分区表父表 ALTER，透传各分区），供治理/悬空键列表展示与即时检测；2) 修正 REQ 默认规则正则 \d{3,} → \d+，与自动生成键 REQ-{id} 一致。

## Spec
docs/10 §8.2.8、§8.2.2（P0-2 修订）

## Deliverable
- [ ] evotrace-server/src/main/resources/db/migration/V19__trace_core_fix.sql

## Acceptance
- [ ] migrate 后 change_event 存在 message 列（含存量分区）
- [ ] ChangeEvent 实体/写入已带 message
- [ ] project_link_rule 中 REQ key 正则已为 \d+
```

### A-BE-1 — ReqKeyService

```markdown
## Summary
需求业务键生成/校验；创建需求时自动写 req_key；列表/详情透出。

## Spec
docs/10 §8.4.2

## Acceptance
- [ ] 不传 reqKey 创建 → REQ-{id}
- [ ] 传已存在键 → 400 REQ_KEY_DUPLICATE
- [ ] GET requirements 返回 reqKey
```

### A-BE-2 — ArtifactLinkService

```markdown
## Summary
边 CRUD、确认/驳回、物理删除 ACTIVE、写 audit。

## Spec
docs/10 §8.4.3（links 部分）

## Acceptance
- [ ] POST link 幂等
- [ ] confirm/reject 状态机正确
- [ ] DELETE 写 audit
```

### A-BE-3 — LinkRuleEngine

```markdown
## Summary
按 project_link_rule 解析 commit message / branch / MR，写 IMPLEMENTS 边；挂钩 CommitHandler / MrMergedHandler。

## Spec
docs/10 §8.3、§8.6.1
配置：evotrace.trace.v2.enabled

## Acceptance
- [ ] 规范 commit 自动 ACTIVE/PENDING
- [ ] 悬空键不造假需求
- [ ] v2.enabled=false 不建边
```

### A-BE-4 — Settings/Rules API

```markdown
## Summary
GET/PUT settings；rules CRUD；seed-defaults。

## Spec
docs/10 §8.4.1

## Acceptance
- [ ] prefix 校验
- [ ] pattern 非法 → PATTERN_INVALID
- [ ] seed-defaults 可重置内置规则
```

### A-BE-5 — Governance API

```markdown
## Summary
summary / unlinked-changes / pending-links / dangling-keys / broken-chains / ignore-orphan / create-requirement。

## Spec
docs/10 §8.4.3

## Acceptance
- [ ] 验收剧本 A3、A4
- [ ] orphan ignore 后不再出现在未关联池
```

### A-BE-6 — Overview + Completeness

```markdown
## Summary
需求全景、版本全景聚合 API + CompletenessScorer。

## Spec
docs/10 §8.4.4、§8.4.5、完整度权重表

## Acceptance
- [ ] 需求 overview 含 links/completeness
- [ ] 版本 overview 含 changes linked/unlinked、gate、bugs
```

### A-BE-7 — ChangeSet rebuild

```markdown
## Summary
按版本时间窗写 SHIPPED_IN；可选刷新 e2e_trace。

## Spec
docs/10 §8.4.5、§8.6.2

## Acceptance
- [ ] rebuild-changeset 后版本全景 requirements/changes 正确
- [ ] 验收剧本 A7
```

### A-FE-1 — 治理中心

```markdown
## Summary
路由 /trace-governance；Summary 卡片；四 Tab；设置抽屉。

## Spec
docs/10 §8.5.1、§8.5.2

## Acceptance
- [ ] 未关联可关联/忽略
- [ ] 待确认可批量确认
- [ ] 悬空键可创建需求并关联
```

### A-FE-2 — 需求全景

```markdown
## Summary
RequirementDetailDrawer 增加全景 Tab + 完整度环。

## Spec
docs/10 §8.5.3

## Acceptance
- [ ] 代码/测试/缺陷/版本 Tab 有数据或空态 CTA
```

### A-FE-3 — Release Cockpit

```markdown
## Summary
ReleaseCockpitView；从 dashboard 最近发布钻取。

## Spec
docs/10 §8.5.4

## Acceptance
- [ ] 门禁条 + 需求表 + 变更统计 + 重建 ChangeSet
```

### A-FE-4 — Graph + 看板

```markdown
## Summary
TraceGraphPanel 简化 stepper；看板展示 reqKey + 完整度色点。

## Spec
docs/10 §8.5.5、§8.5.6

## Acceptance
- [ ] 看板可见 reqKey；点击进全景 Tab
```

### A-QA-1 — 验收与对接约定

```markdown
## Summary
跑通 A1–A9；06-对接指南增加 commit/分支 req_key 约定。

## Acceptance
- [ ] §8.7 全部勾选
- [ ] docs/06 有约定示例
```

### A-DOC-1 — 文档同步

```markdown
## Summary
登记新 API 到 03；01 里程碑 M-Trace-A；确认 README 已链到 10/11。

## Acceptance
- [ ] 03/01/README 已更新
```

---

## 用 gh 批量创建（可选）

在仓库根目录执行（需已 `gh auth login`）：

```bash
# Epic
gh issue create --title "[Phase A] Trace Core — 需求↔代码链路打底" --body "$(cat <<'EOF'
## Summary
打通需求↔代码结构化关联。规格见 docs/10 §8，子任务见 docs/11-PhaseA-Issue清单.md。

## Note
A-DB-1 已落地：V18__trace_core.sql
EOF
)"

# 示例：创建单个子 issue
gh issue create --title "[Phase A][BE] ReqKeyService：生成/校验/创建回写" --body "$(cat <<'EOF'
依赖：A-DB-1（V18 已合并）
规格：docs/10 §8.4.2
清单：docs/11-PhaseA-Issue清单.md → A-BE-1
EOF
)"
```

---

## 修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-08-09 | 初版；A-DB-1 标记 done（V18__trace_core.sql） |
| 2026-08-09 | 新增 A-DB-2（V19 P0 修订）；A-BE-3/A-BE-5 依赖补 A-DB-2；Wave 1 并行 A-DB-2 |
