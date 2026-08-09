# EvoTrace — 系统演化追踪与智能分析平台

EvoTrace 打通「需求 → 代码 → 测试 → 缺陷 → 发布」全链路，借助 AI 把海量变更信息转译为业务语义，为 PM、Dev、QA、Ops 各自提供关心的视图。

---

## 文档导航

| 文档 | 说明 |
| --- | --- |
| [01-需求文档.md](docs/01-需求文档.md) | PRD V2.0 — 功能需求、验收标准、里程碑 |
| [02-架构设计文档.md](docs/02-架构设计文档.md) | 架构设计 V2.0 — 模块划分、数据模型、核心算法 |
| [03-API接口文档.md](docs/03-API接口文档.md) | 完整 API 参考（开放API + 控制台API + PM/QA） |
| [04-部署运维手册.md](docs/04-部署运维手册.md) | 部署、配置、Kafka 运维、故障排查 |
| [05-开发者接入指南.md](docs/05-开发者接入指南.md) | Java SDK / CLI / Webhook / REST API 接入指引 |
| [06-对接指南.md](docs/06-对接指南.md) | 快速对接 — 三步接入、签名协议、事件速查、常见错误 |
| [05-开源对标设计与优化.md](docs/05-开源对标设计与优化.md) | 竞品对标（Gitness/Reflow/MeterSphere/GitLab）与迭代方向 |
| [08-TAPD企业版功能分析报告.md](docs/08-TAPD企业版功能分析报告.md) | TAPD 企业版全功能分析（分类目录/功能描述/对比/建议） |
| [09-EvoTrace与TAPD差距分析.md](docs/09-EvoTrace与TAPD差距分析.md) | EvoTrace 与 TAPD 逐模块差距与补建优先级（P0/P1/P2） |

---

## 技术基线

JDK 21 · Spring Boot 4.0 · Spring AI 2.0 · PostgreSQL 16(pgvector) · Neo4j 5 · Kafka 3 · MinIO · Redis · ES 8 · Vue 3

---

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `evotrace-common` | 共享内核（Result、错误码） |
| `evotrace-protocol` | 统一事件协议 Envelope v1 |
| `evotrace-server` | 平台服务端——116 个 Java 源文件，Modulith 架构 |
| `evotrace-sdk-java` | Spring Boot Starter——零侵入自动上报（依赖/配置/API 清单） |
| `evotrace-cli` | 多语言 CLI 扫描器（Go/Python/Vue/Node） |
| `evotrace-ai-prompts` | 8 个 Prompt 模板（摘要/用例/PR/审查/原型/需求展开等） |
| `evotrace-ui` | Vue3 Web 控制台——12 个功能页面 |
| `evotrace-vscode` | VS Code / Cursor 插件 |
| `evotrace-idea` | IntelliJ IDEA 插件（文件历史 / 项目面板） |

---

## 快速开始

```bash
# 1. 拉起依赖中间件
docker compose -f deploy/docker-compose.yml up -d

# 2. 构建
mvn -T 1C clean install -DskipTests

# 3. 启动服务端（Flyway 自动建表 + 种子数据）
mvn -pl evotrace-server spring-boot:run

# 4. 启动前端
cd evotrace-ui && npx vite
```

浏览器打开 **http://localhost:5173** · 默认账号 `admin` / `admin123`

---

## 接入方式

- **Java（Spring Boot）**：引入 `evotrace-spring-boot-starter`，配置 `evotrace.project-key/api-key`
- **Go / Python / Vue / Node**：CI 中 `evotrace scan --project-key xxx --api-key yyy`
- **GitLab**：Webhook → `/open-api/v1/webhooks/gitlab`
- **GitHub**：Webhook → `/open-api/v1/webhooks/github`
- **任意系统**：按 Envelope v1 协议 POST `/open-api/v1/events`（HMAC-SHA256 签名）

---

## 功能全景

| 角色 | 功能 | 路由 |
|------|------|------|
| **All** | 项目总览（统计+趋势+发布） | `/dashboard` |
| **Admin** | 接入管理（项目/凭证/应用） | `/integration` |
| **Dev** | 演化时间线 + AI 摘要 + 文件历史 | `/timeline` |
| **Dev/TL** | 版本对比报告（5 维度） | `/compare` |
| **Dev/TL** | 代码审查 + PR 描述生成 + 审查回写 Git | `/code-review` |
| **Dev/TL** | 智能分析（热点/破坏性变更/风险评分） | `/analysis` |
| **PM** | 需求看板（看板流转 + 质量门禁） | `/pm` |
| **All** | AI 演化问答 | `/qa` |
| **QA** | QA 测试面板（门禁配置/测试计划/用例/AI 用例/追溯矩阵） | `/qa-dashboard` |
| **All** | 变更订阅 + 通知 | `/subscriptions` |
| **All** | AI 模型配置化（多模型路由） | `/model-config` |
| **All** | 个人工作台（需求/缺陷/反馈/规则聚合） | `/workbench` |
| **PM/QA/Ops** | 研效度量（交付率/逃逸率/吞吐/周期） | `/metrics` |
| **PM/QA** | 缺陷管理（状态流转 + 看板 + 追溯） | `/bugs` |
| **PM/Ops** | 自动化规则引擎（事件触发动作） | `/automation` |
| **PM** | 反馈管理（AI 转需求/缺陷） | `/feedback` |
| **PM** | 项目成员与角色权限 | `/members` |
| **Dev** | VS Code / IDEA 右键文件 → 演化历史 | 插件 |

---

## 数据库迁移

| 版本 | 内容 |
| --- | --- |
| V1 | 核心变更域——workspace/project/change_event/snapshot/ai |
| V2 | 用户认证——sys_user |
| V3 | 分析引擎——依赖图/订阅/破坏性变更/风险评分/通知 |
| V4 | PM-QA-Ops 全链路——需求/测试/缺陷/质量门禁/端到端追踪 |
| V5 | 代码审查——review 会话/评论/推送 |
| V6 | 核心闭环——语义单元/AI 摘要任务 |
| V7 | 分区——事件表按月分区 |
| V8 | 测试计划——test_plan/test_case/test_execution/缺陷回溯 |
| V9 | AI 模型配置——多模型路由表 |
| V10 | PM 工作台——需求生命周期/文档/原型/任务 |
| V11 | 质量门禁规则——可配置阈值 |
| V12 | 代码审查回写——MR 描述/回写 Git |
| V13 | Feishu Bitable 双向同步 |
| V14 | API 接口调试——接口库/Mock/测试用例/环境 |
| V15 | 测试平台增强——用例版本/导入导出/报告/定时 |
| V16 | UI 测试——Selenium 用例 |
| V17 | DevOps 治理——自动化规则/项目成员/反馈/研效度量 |

---

## 项目状态

**V2.0 已完成** — 116+ 个服务端源文件，40+ 个 API 端点，12+ 个前端页面，VS Code + IDEA 双插件，全链路打通并上线 http://43.155.130.69。

**迭代方向**（详见 [05-开源对标设计与优化.md](docs/05-开源对标设计与优化.md)）：
- **P0** 需求状态时间线 UI · 质量门禁规则配置化
- **P1** 单 MR 描述生成 + 审查回写 Git · AI 测试用例生成 + 需求追溯矩阵
- **P2** 测试计划编排（拖拽排序）

**TAPD 差距补建**（详见 [09-EvoTrace与TAPD差距分析.md](docs/09-EvoTrace与TAPD差距分析.md)）：
- **P0** 研效度量深化（交付率/逃逸率/吞吐/周期）· 缺陷管理闭环（流转+看板+追溯）· 自动化规则引擎
- **P1** 多维报表 · 需求↔代码↔缺陷追溯 · 角色权限细分
- **P2** 个人工作台聚合 · 缺陷看板 · AI 反馈转需求
