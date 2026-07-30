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

---

## 技术基线

JDK 21 · Spring Boot 4.0 · Spring AI 2.0 · PostgreSQL 16(pgvector) · Neo4j 5 · Kafka 3 · MinIO · Redis · ES 8 · Vue 3

---

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `evotrace-common` | 共享内核（Result、错误码） |
| `evotrace-protocol` | 统一事件协议 Envelope v1 |
| `evotrace-server` | 平台服务端——68 个 Java 源文件，Modulith 架构 |
| `evotrace-sdk-java` | Spring Boot Starter——零侵入自动上报 |
| `evotrace-cli` | 多语言 CLI 扫描器（Go/Python/Vue/Node） |
| `evotrace-ai-prompts` | Prompt 模板与 Few-shot 样本 |
| `evotrace-ui` | Vue3 Web 控制台——10 个功能页面 |
| `evotrace-vscode` | VS Code / Cursor 插件 |

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
| **Dev** | 演化时间线 + AI 摘要 | `/timeline` |
| **Dev/TL** | 版本对比报告（5 维度） | `/compare` |
| **Dev/TL** | 智能分析（热点/破坏性变更/风险评分） | `/analysis` |
| **PM** | 需求看板（Kanban 流转） + 质量门禁 | `/pm` |
| **QA** | 测试推荐 + Bug 追溯 + 发布准入 | `/qa` |
| **All** | AI 演化问答 | `/qa`（AI Q&A） |
| **All** | 变更订阅 + 通知 | `/subscriptions` |
| **Dev** | VS Code 右键文件 → 演化历史 | 插件 |

---

## 数据库迁移

| 版本 | 内容 |
| --- | --- |
| V1 | 核心变更域——workspace/project/change_event/snapshot/ai |
| V2 | 用户认证——sys_user |
| V3 | 分析引擎——依赖图/订阅/破坏性变更/风险评分/通知 |
| V4 | PM-QA-Ops 全链路——需求/测试/缺陷/质量门禁/端到端追踪 |

---

## 项目状态

**V2.0 已完成** — 68 个源文件，30+ 个 API 端点，10 个前端页面，1 个 VS Code 插件，全链路打通。
