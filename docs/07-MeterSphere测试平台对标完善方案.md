# MeterSphere 测试平台对标完善方案

## 1. 调研结论：MeterSphere 架构与技术选型

### 1.1 整体架构

MeterSphere 是一站式开源持续测试平台，覆盖**测试跟踪、接口测试、UI 测试、性能测试**四大模块，核心价值是"集 Postman 易用 + JMeter 灵活"，并内置 AI 助手。

```
┌─────────────────────────── 前端（Vue.js + Arco Design）──────────────────────────┐
│  工作台 │ 测试跟踪 │ 接口测试 │ UI测试 │ 性能测试 │ 报告统计 │ 项目设置 │ 系统设置 │
└──────────────────────────────────┬───────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────────────────┐
│                      后端（Spring Cloud 微服务，业务模块化拆分）                    │
│  backend/framework（基础） · ai-engine（AI 助手） · plugin（插件体系）              │
└──────────────────────────────────┬───────────────────────────────────────────────┘
                                   │
┌──────────────┬──────────┬────────┴───────┬───────────┬───────────────┬───────────┐
│    MySQL     │   Redis  │      Kafka      │  MinIO    │  Prometheus   │  Eureka   │
│ 结构化数据     │ 会话/队列 │ JMeter结果管道   │ 对象存储   │  压力机监控     │ 服务注册   │
└──────────────┴──────────┴─────────────────┴───────────┴───────────────┴───────────┘
                                   │
┌──────────────────────────────────▼───────────────────────────────────────────────┐
│  测试引擎：JMeter（接口/性能） · Selenium Grid（UI） · Node Controller（资源池）   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

**技术栈**：后端 Spring Cloud、前端 Vue.js、中间件 MySQL/Kafka/Redis/MinIO/Prometheus、测试引擎 JMeter + Selenium，基础设施 Docker/Kubernetes。

### 1.2 关键设计理念（可借鉴点）

| 设计理念 | MeterSphere 做法 | 对 EvoTrace 的启示 |
|---|---|---|
| 分层租户模型 | 系统→组织→项目，三级 + 自定义角色 | 项目级数据隔离已具备，可补充角色权限细分 |
| 插件化 | 第三方集成（Jira/TAPD/禅道）、CI（Jenkins）、数据库驱动、协议扩展 | EvoTrace 已有 Jira/飞书同步，可扩展为插件化同步 |
| AI 赋能 | `ai-engine` 模块生成功能用例/接口用例 | EvoTrace 已有 `AiTestCaseGenerator`，可横向扩展 |
| 场景编排 | 多接口串联 + 变量提取 + 前后置脚本 + 逻辑控制器 | EvoTrace 只有单用例 http 步骤，缺"场景"层 |
| 报告体系 | 可视化报告 + 分享 + 导出归档 + 覆盖率 | EvoTrace 有 `planReport`，缺可视化/分享/导出 |
| 环境管理 | 环境配置 + 全局变量 + 环境切换 | EvoTrace 接口调试有环境，测试计划执行未绑定 |
| 兼容标准 | 兼容 JMeter/Postman/Swagger 导入 | EvoTrace 已有 OpenAPI/Postman/cURL/Apifox 导入 |

### 1.3 关键功能实现逻辑

- **测试用例管理**：用例可挂模块树、自定义字段、版本记录、评审流程；支持脑图模式编辑；回收站保护。
- **接口测试**：接口定义（HTTP/TCP/Dubbo）→ 接口用例（调试）→ 场景自动化（多接口编排）→ 测试报告；Mock 服务、变量提取、响应断言、前后置脚本。
- **性能测试**：兼容 JMeter 脚本，分布式压测（Node Controller + Kafka 结果管道 + Prometheus 监控）。
- **测试执行**：测试计划 → 计划项 → 执行器 → 结果回流（Kafka/Data Streaming）→ 报告统计。

---

## 2. 现状分析：EvoTrace 测试模块现状与差距

### 2.1 已具备能力（基于现有代码）

| 能力域 | 现有实现 | 关键文件 |
|---|---|---|
| 用例管理 | 模块树（parent_id + node_type）、CRUD、步骤 JSON、test_type/priority/tags、需求/缺陷关联 | `TestCaseService`、V4/V8 迁移 |
| 测试计划 | 计划 CRUD、计划项（sort_order）、状态、执行、轻量排序、从推荐生成计划 | `TestPlanService`、V8 迁移 |
| 服务端执行 | `http` 步骤执行器 + 断言（statusCode/bodyContains/bodyNotContains/responseTimeMs） | `TestExecutionRunner` |
| 执行记录 | 执行记录、趋势（执行/缺陷）、质量门禁 | `TestExecutionService`、`QualityGateChecker` |
| AI 用例 | 变更驱动 AI 生成用例建议 | `AiTestCaseGenerator` |
| 接口调试 | 接口清单、调试转发、Mock、多格式导入、环境、用例 | `ApiDebugView`、`ApiDebugService`、`ApiImporter`、`ApiMockController` |
| 缺陷追溯 | Bug↔Commit↔用例追溯、需求追溯矩阵 | `BugTraceService`、`traceMatrix` |
| 外部同步 | Jira 配置、飞书 Bitable 双向同步 | `FeishuBitableService` 等 |

### 2.2 与 MeterSphere 的差距（Gap 分析）

| # | 差距 | 说明 | 优先级 |
|---|---|---|---|
| G1 | 用例无版本/历史、无自定义字段、无批量导入导出 | 用例管理能力偏弱，无法应对团队协作与数据迁移 | P0 |
| G2 | 无"场景/场景集"（多接口编排） | `TestExecutionRunner` 仅支持单用例 http 步骤，缺变量提取/前后置脚本/逻辑控制 | P0 |
| G3 | 接口用例与 test_plan 未打通 | `api_test_case` 独立于测试计划，接口用例无法进计划执行 | P0 |
| G4 | 测试报告缺可视化/分享/导出/覆盖率图表 | `planReport` 仅返回 JSON，无美观报告页 | P1 |
| G5 | 测试环境未接入计划执行 | 用例执行只认 url 硬编码，无环境/全局变量绑定 | P1 |
| G6 | 无定时调度与 CI 触发 | 无法融入持续交付 | P2 |
| G7 | 无性能测试 | 完全缺失 | P2 |
| G8 | AI 能力单一 | 仅生成功能用例，未生成接口用例/报告摘要/缺陷分析 | P2 |

---

## 3. 完善方案总览

> 技术约定：与现有架构保持一致 —— 单体 Spring Boot 服务 + `JdbcTemplate` + PostgreSQL（JSONB）+ Vue 3 + Element Plus。**不引入 Kafka/Redis/MinIO 等重型中间件**，纯后端执行器用 `HttpClient`，异步任务用 JDK 虚拟线程/`@Scheduled`，文件用本地 + 公开 URL 归档。所有改动走 Flyway 增量迁移（现最高 V14，新增 V15+）。

分期原则：**P0 打地基（用例能力 + 场景编排 + 接口用例打通）→ P1 提体验（报告 + 环境）→ P2 上自动化（调度/CI/性能/AI）**。每期独立可上线、可回滚。

```
P0（本期 MVP）        P1（增强）              P2（进阶）
用例版本/自定义字段    可视化报告/分享/导出     定时任务 + CI 触发
场景编排引擎          环境/全局变量接入执行     性能测试（JMeter 兼容）
接口用例进计划         报告统计分析              AI：接口用例/报告摘要/缺陷分析
Excel 批量导入导出    用例脑图/评审（可选）
```

---

## 4. 各模块详细方案

### M1 测试用例设计与管理模块优化（G1，P0）

**目标**：补齐用例版本、自定义字段、批量导入导出，让用例成为可复用资产。

**技术路径**：
1. **用例版本**：V15 迁移新增 `test_case_version` 表，`test_case` 每更新一次写入一条快照（title/description/steps/related_files/related_apis/tags + 版本号 + 变更人/时间）。`TestCaseService.update` 内先快照再更新（复用现有 `whitelist` 与 `snake` 工具）。
2. **自定义字段**：`test_case` 增加 `custom_fields JSONB NOT NULL DEFAULT '{}'`；新增项目级 `project_case_field` 表定义字段元数据（key/label/type/options/required）。`list/detail` 透出，`create/update` 走白名单外的 `custom_fields` 透传。
3. **批量导入/导出**：新增 `CaseImportExportService`，导出生成 Excel（含模块树展开、自定义字段、步骤）；导入解析 Excel 自动建模块树 + 用例。**复用 Apache POI**（新增依赖，轻量）。
4. **API**：`TestPlanController` 增加 `GET /cases/versions/{caseId}`、`GET /cases/export`、`POST /cases/import`、`PUT /cases/{id}/custom-fields`。
5. **前端**：`QADashboardView` 用例抽屉增加"历史版本"Tab、自定义字段渲染、工具栏"导出/导入"按钮。

**改动清单**：
- 新增 `db/migration/V15__test_case_enhance.sql`
- 新增 `server/testplan/CaseVersionService.java`、`CaseImportExportService.java`
- 修改 `TestCaseService.java`、`TestPlanController.java`
- 修改 `evotrace-ui/QADashboardView.vue`、`api/index.ts`

**验收标准（AC）**：
- 用例每次编辑后可在历史版本中回看/对比，且版本号递增。
- 自定义字段可配置并可随用例增删改查。
- 导出 Excel 再导入可完整还原模块树、字段、步骤（往返一致）。
- 后端 `mvn compile`、前端 `vue-tsc + vite build` 通过。

---

### M2 接口自动化与测试脚本集成（G2 + G3，P0）

**目标**：把 API 调试的能力升级为"场景编排"，并让接口用例进入测试计划执行，形成"接口自动化"闭环。

**技术路径**：
1. **场景模型**：V15 新增 `api_scenario`（project、name、description、enabled、chart_json 场景 DAG）+ `api_scenario_step`（scenario_id、sort_order、step_type=HTTP/SQL/EXTRACT/ASSERT/IF、config_json）。
2. **场景执行引擎**：新增 `ApiScenarioService`，复用 `ApiDebugService.debug` 做请求转发，支持：
   - **变量提取**：`extract` 步骤从上一响应 JSONPath 提取 → 存入场景上下文 Map。
   - **前后置脚本**：轻量内置 JS 表达式（Nashorn 已被 JDK17 移除，改用 **GraalJS** 或受限的自定义表达式求值器；为控制复杂度本期先支持 `${var}` 变量替换 + 简单算术/拼接，JS 脚本列为 P2）。
   - **逻辑控制**：支持 `IF(条件：上一步断言通过/变量==xx)` 分支、`fail-fast`。
   - **断言**：复用 `TestExecutionRunner` 的断言逻辑（抽成共享 `AssertionEvaluator`）。
3. **接口用例进计划**：`test_plan_item` 增加 `item_type VARCHAR DEFAULT 'CASE'`（CASE/SCENARIO），`TestExecutionRunner.runPlan` 按类型分发：CASE 走原逻辑，SCENARIO 走 `ApiScenarioService`。
4. **API**：`TestPlanController` 增加 `/scenarios` CRUD、`/scenarios/{id}/run`、`/scenarios/{id}/debug`；`/plans/{id}/items` 支持 `itemType=SCENARIO`。
5. **前端**：`ApiDebugView` 增加"场景"Tab（编排画布 + 步骤编辑 + 运行结果）；`QADashboardView` 计划项支持添加场景。

**改动清单**：
- 新增 `db/migration/V15__api_scenario.sql`
- 新增 `server/api/ApiScenarioService.java`、`server/testplan/AssertionEvaluator.java`（抽取公用）
- 修改 `TestExecutionRunner.java`（按 item_type 分发）、`TestPlanController.java`、`TestPlanService.java`
- 修改 `evotrace-ui/ApiDebugView.vue`、`QADashboardView.vue`、`api/index.ts`

**验收标准（AC）**：
- 场景可编排 ≥2 个接口，前一接口响应变量可在后一请求中被 `${var}` 替换。
- 场景运行返回逐步结果（每步状态码/耗时/断言/提取变量），失败可定位到具体步骤。
- 场景可加入测试计划并随计划批量执行，执行记录正确回流。
- 后端/前端构建通过。

---

### M3 测试报告生成与分析功能增强（G4，P1）

**目标**：把 JSON 报告升级为可视化、可分享、可导出的报告页，并补充覆盖率统计。

**技术路径**：
1. **报告模型**：V15 新增 `test_report`（plan_id、name、status、summary_json、start/end、created_by、share_token）。`TestPlanService.report` 结果落库。
2. **可视化报告页**：前端新增 `ReportView.vue`（路由 `/test-report/:id`）：通过率环形图（ECharts）、按 test_type/priority 的通过率柱状图、用例明细表（状态/耗时/断言）、失败用例列表。无报告时展示"暂无数据"。
3. **分享与导出**：`report` 生成 `share_token`，新增 `GET /reports/share/{token}`（免登录只读）；导出为 PDF/HTML（前端导出视口，或用后端模板渲染 HTML 字符串）。
4. **覆盖率统计**：`TraceMatrix` 已算用例覆盖度，报告页叠加"需求→用例→执行→缺陷"覆盖率卡片（复用 `testCaseService.traceMatrix`）。
5. **API**：`TestPlanController` 增加 `GET /reports`、`GET /reports/{reportId}`、`POST /reports/{reportId}/share`、`GET /reports/share/{token}`。

**改动清单**：
- 新增 `db/migration/V15__test_report.sql`
- 修改 `TestPlanService.report`（落库 + share_token）
- 新增 `server/testplan/TestReportService.java`、`TestReportController.java`
- 新增 `evotrace-ui/ReportView.vue`、路由；修改 `QADashboardView.vue`（报告入口）、`api/index.ts`

**验收标准（AC）**：
- 计划执行后生成报告，报告页展示通过率/明细/失败用例，图表渲染正常。
- 分享链接免登录可查看，token 随机且可失效。
- 报告可导出为 PDF/HTML。
- 后端/前端构建通过。

---

### M4 测试环境配置管理（G5，P1）

**目标**：让测试执行绑定环境与全局变量，支持多环境回归。

**技术路径**：
1. **复用环境模型**：`api_environment`（V14 已建：name/base_url/headers）即为环境资产。V15 为 `test_plan` 增加 `environment_id BIGINT REFERENCES api_environment(id)`。
2. **执行期环境解析**：`TestExecutionRunner`/`ApiScenarioService` 执行时，若计划绑定环境，则：
   - 用例/场景步骤中的变量 `${baseUrl}`、`${env.xxx}` 用环境 base_url + headers 替换；
   - 未绑定环境时回退原始 url（保证向后兼容）。
3. **全局变量**：`api_environment` 增加 `variables JSONB`（key/value），执行上下文注入。
4. **前端**：`QADashboardView` 计划抽屉增加"执行环境"选择；`ApiDebugView` 环境弹窗增加"全局变量"编辑。

**改动清单**：
- 修改 `db/migration/V15__test_env.sql`（test_plan.environment_id、api_environment.variables）
- 修改 `TestExecutionRunner.java`、`ApiScenarioService.java`（环境解析）
- 修改 `TestPlanService.java`、`evotrace-ui/QADashboardView.vue`、`ApiDebugView.vue`、`api/index.ts`

**验收标准（AC）**：
- 计划绑定测试环境后，执行请求自动指向环境 base_url 并携带环境 headers/变量。
- 未绑定环境时行为与现状完全一致（无回归）。
- 后端/前端构建通过。

---

### M5 进阶：定时调度 / CI / 性能 / AI（G6+G7+G8，P2）

**目标**：融入持续交付，补齐性能测试与 AI 增强。

**技术路径**（均为独立可下线模块）：
1. **定时调度**：新增 `TestScheduleService`，基于 `@Scheduled` + `test_schedule` 表（plan_id、cron、enabled、last_run），定时触发计划执行；支持"订阅触发"（复用订阅/Webhook 事件，变更后自动跑计划）。
2. **CI 触发**：新增 `POST /projects/{key}/testplan/plans/{id}/run`（当前用户触发）+ 一个无鉴权或 token 鉴权的 `POST /ci/run` 端点，供 Jenkins/GitHub Actions 调用。
3. **性能测试**：新增 `performance_test` 表 + `PerformanceTestController`，**以 JMeter .jmx 脚本为输入**（上传解析），后端用 `HttpClient` 并发线程池做轻量压测（区别于 MeterSphere 的分布式 Node Controller，本期单机并发），输出 TPS/RT/错误率 + 报告。列为 P2 可选，避免过度建设。
4. **AI 增强**：扩展 `AiTestCaseGenerator` → 新增 `AiApiCaseGenerator`（从接口 schema 生成接口用例建议）、`AiReportSummarizer`（报告摘要）、`AiBugAnalyzer`（缺陷根因分析提示）。复用 `ModelRouter.clientFor("TEST_GENERATION")`。

**验收标准（AC）**：
- 定时/CI 触发可执行计划并回流记录。
- 性能测试可配置并发/时长，输出 TPS/RT/P95/错误率。
- AI 接口用例/报告摘要产出可直接展示，不污染数据。

---

## 5. 统一技术实现路径

| 层 | 约定 |
|---|---|
| 数据库 | Flyway 增量迁移（V15+），JSONB 存结构与上下文，索引按 `project_id` 前缀 |
| 后端 | 单体 Spring Boot Service + `JdbcTemplate`；执行器用 `HttpClient`；白名单字段校验沿用 `whitelist/snake` 模式；错误用 `IllegalArgumentException` + `Result.fail` |
| 异步 | JDK 虚拟线程 / `@Scheduled`，不引入消息队列 |
| 前端 | Vue 3 + Element Plus + Pinia；操作按钮沿用 `.ops-btn` 样式规范 |
| 变量/断言 | 抽公共 `AssertionEvaluator`，`${var}` 变量统一解析器，多执行器复用 |
| 兼容 | 新功能默认关闭/向后兼容，未绑定环境/未配场景时行为与现状一致 |

---

## 6. 开发排期

| 阶段 | 周期 | 交付内容 |
|---|---|---|
| P0-M1 | 第 1–2 周 | 用例版本、自定义字段、Excel 批量导入导出 |
| P0-M2 | 第 3–5 周 | 场景编排引擎、接口用例进计划、断言抽公共 |
| 里程碑 | 第 5 周末 | **P0 上线**：接口自动化闭环可用 |
| P1-M3 | 第 6–7 周 | 可视化报告、分享/导出、覆盖率卡片 |
| P1-M4 | 第 8 周 | 环境/全局变量接入计划执行 |
| 里程碑 | 第 8 周末 | **P1 上线**：报告 + 环境完备 |
| P2-M5 | 第 9–12 周 | 定时/CI、性能测试、AI 接口用例/报告摘要/缺陷分析 |
| 里程碑 | 第 12 周末 | **P2 上线**：持续测试 + AI 增强 |

> 说明：排期按 1 名全栈开发者估算；M2 为最大模块，若资源紧张可将"前后置 JS 脚本"推迟至 P2。

---

## 7. 质量验收标准（总）

1. **构建质量**：后端 `mvn compile/install`、前端 `vue-tsc + vite build` 全绿，无新增告警。
2. **数据一致性**：所有新表走 Flyway 迁移，IBATIS/JDBC 事务包裹多表写入（用例快照、场景执行、报告落库）。
3. **兼容性**：新功能默认关闭，未配置场景/环境/自定义字段时，现有用例执行、计划、报告、接口调试行为与现状完全一致（无回归）。
4. **可回滚**：每个 P 阶段独立可上线，迁移可回退，部署遵循 `deploy/deploy-fe.sh`（仅前端）与 `deploy/deploy.sh`（含后端重启）。
5. **可观测**：执行器、场景、报告关键路径打 `log.info`，错误含上下文（project/caseId/planId/scenarioId）。
6. **安全**：报告分享用随机 token、免登录只读；CI 端点用 token 鉴权，避免越权。
7. **性能**：单接口/场景执行同步返回；批量（计划/场景集）执行限制并发与超时（复用 `max-steps`、`request-timeout-ms` 配置），避免拖垮服务。
8. **文档**：每期上线同步更新 `07-本方案` 与实际实现差异，并在 `01-需求文档`、`03-API接口文档` 登记新接口。

---

## 8. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| 场景编排复杂度 | 前后置脚本/逻辑控制实现成本高 | 分期：本期仅 `${var}` + IF 分支，JS 脚本推 P2 |
| 性能测试单机瓶颈 | 无分布式资源池 | 明确单机压测定位，JMeter 分布式列为远期 |
| 引入 POI 体积 | 新增依赖增大 jar | 仅用于导入导出，按需加载类 |
| 与现有执行链路冲突 | 计划执行逻辑改动有回归风险 | 用 item_type 分发 + 环境可选，做兼容回归验证 |