# EvoTrace API 接口文档

| 项目 | 内容 |
| --- | --- |
| 版本 | V2.0 |
| 基础路径 | 控制台: `http://localhost:8080/api/v1` / 开放API: `http://localhost:8080/open-api/v1` |
| 鉴权 | 控制台: `Authorization: Bearer <JWT>` / 开放API: `X-EvoTrace-Api-Key` + `X-EvoTrace-Signature` |
| 格式 | JSON, 统一响应 `{ success, code, message, data }` |

---

## 1. 开放API（接入方）

### 1.1 上报事件

```http
POST /open-api/v1/events
Headers:
  X-EvoTrace-Api-Key: evo_xxxx
  X-EvoTrace-Signature: <HMAC-SHA256(apiSecret, body)>
  Content-Type: application/json
```

**请求体 (Envelope v1)**:

```json
{
  "protocolVersion": "v1",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "projectKey": "mall",
  "appKey": "order-service",
  "eventType": "CODE_COMMIT",
  "occurredAt": "2026-07-30T14:30:00+08:00",
  "source": "JAVA_SDK",
  "idempotencyKey": "gitlab:project/123:push:abc",
  "payload": {
    "repoUrl": "https://github.com/demo/app.git",
    "branch": "main",
    "commitSha": "abc123def456",
    "parentShas": [],
    "authorName": "zhangsan",
    "authorEmail": "zhangsan@example.com",
    "message": "feat: 用户登录模块",
    "files": [
      { "oldPath": null, "newPath": "src/LoginService.java",
        "kind": "ADDED", "addLines": 45, "delLines": 0, "diffBlobRef": null }
    ]
  },
  "blobRef": null
}
```

**响应**:

```json
{ "success": true, "code": "0", "message": "ok",
  "data": { "eventId": "550e8400-...", "duplicated": false } }
```

### 1.2 上传大对象

```http
POST /open-api/v1/blobs
Content-Type: text/plain
Body: <raw content>
→ { "success": true, "data": { "blobRef": "uuid" } }
```

### 1.3 Webhook

```http
POST /open-api/v1/webhooks/gitlab
POST /open-api/v1/webhooks/github
```

GitLab 自动识别 `object_kind`: push / merge_request / tag_push
GitHub 自动识别 `X-GitHub-Event`: push / pull_request / create

---

## 2. 认证

### 2.1 登录

```http
POST /api/v1/auth/login
{ "username": "admin", "password": "admin123" }
→ { "data": { "token": "eyJ...", "displayName": "管理员", "role": "ADMIN" } }
```

---

## 3. 项目与接入

### 3.1 项目管理

```http
GET    /api/v1/projects                              # 项目列表
POST   /api/v1/projects                              # 创建项目
Body: { "projectKey": "mall", "name": "商城系统", "repoUrl": "..." }
→ { "data": { "apiKey": "evo_...", "apiSecret": "..." } }
```

### 3.2 凭证管理

```http
GET    /api/v1/projects/{key}/credentials            # 凭证列表
POST   /api/v1/projects/{key}/credentials/rotate     # 轮换凭证（旧凭证全部吊销）
DELETE /api/v1/projects/{key}/credentials/{id}       # 吊销指定凭证
```

### 3.3 应用管理

```http
GET    /api/v1/projects/{key}/applications           # 应用列表
POST   /api/v1/projects/{key}/applications           # 创建应用
PUT    /api/v1/projects/{key}/applications/{appKey}  # 更新应用
```

---

## 4. 演化追踪

### 4.1 时间线

```http
GET /api/v1/projects/{key}/timeline?app=&type=&limit=100
→ { "data": [{ "eventId", "eventType", "appKey", "branch", "commitSha",
               "author", "occurredAt", "summaryStatus", "summary", "iterationTitle" }] }
```

### 4.2 版本

```http
GET /api/v1/projects/{key}/releases
→ { "data": [{ "version", "baseCommit", "tag", "env", "releasedAt", "releaseNote" }] }
```

### 4.3 版本对比

```http
GET /api/v1/projects/{key}/compare?from=v2.4.9&to=v2.5.0
→ { "data": { "fromVersion", "toVersion", "stats", "changes",
              "apis", "dependencies", "configs", "schemas" } }
```

### 4.4 文件历史

```http
GET /api/v1/files/history?path=TimeoutCloseJob&projectKey=mall
→ { "data": [{ "eventId", "eventType", "commitSha", "author", "changeKind",
               "addLines", "delLines", "summary" }] }
```

---

## 5. 智能分析

### 5.1 代码热点

```http
GET /api/v1/projects/{key}/analysis/hotspots?days=30
→ { "data": { "topChangedFiles", "bugProneFiles", "coChangedFiles", "moduleHotspots" } }
```

### 5.2 破坏性变更

```http
GET  /api/v1/projects/{key}/analysis/breaking-changes
POST /api/v1/projects/{key}/analysis/breaking-changes/{id}/acknowledge
```

### 5.3 影响面分析

```http
GET /api/v1/projects/{key}/analysis/impact?fromVersion=v1&toVersion=v2
→ { "data": { "affectedNodeCount", "affectedServices", "directCallers", "suggestedRegression" } }
```

### 5.4 发布风险评分

```http
GET  /api/v1/projects/{key}/analysis/risk-score?fromVersion=v1&toVersion=v2
GET  /api/v1/projects/{key}/analysis/risk-score/history
→ { "data": { "totalScore": 10, "riskLevel": "低风险", "subScores": {...}, "explanation": "..." } }
```

### 5.5 依赖图

```http
GET  /api/v1/projects/{key}/analysis/top-impact-endpoints?limit=10
POST /api/v1/projects/{key}/analysis/dependencies
Body: { "caller": "order-service:POST /order", "callee": "pay-service:POST /pay", "callType": "REST" }
```

---

## 6. PM 面板

### 6.1 仪表盘

```http
GET /api/v1/pm/dashboard?projectKey=mall
→ { "data": { "requirementStats", "bugStats", "nextRelease", "notifications", "recentBugs" } }
```

### 6.2 需求管理

```http
GET  /api/v1/pm/requirements?projectKey=mall&status=DEVELOPING
POST /api/v1/pm/requirements?projectKey=mall
Body: { "title", "priority", "status", "productManager", "assignedTo",
        "targetVersion", "prototypeUrl", "designUrl", "description" }
PUT  /api/v1/pm/requirements/{id}/status?status=TESTING&actor=PM
```

### 6.3 质量门禁

```http
POST /api/v1/pm/quality-gate/check?projectKey=mall&targetVersion=v2.5.0&checkedBy=PM
GET  /api/v1/pm/quality-gate/history?projectKey=mall
→ { "data": { "passed": false, "score": 70, "checks": {...}, "verdict": "..." } }
```

### 6.4 通知

```http
GET /api/v1/pm/notifications?projectKey=mall&role=QA
PUT /api/v1/pm/notifications/{id}/read
```

---

## 7. QA 面板

### 7.1 测试推荐

```http
GET /api/v1/pm/test-recommendation?projectKey=mall&fromVersion=v2.4.9&toVersion=v2.5.0
→ { "data": { "recommendedTests", "p0Count", "p1Count", "regressionScope", "riskLevel" } }
```

### 7.2 发布准入

```http
GET /api/v1/pm/release-readiness?projectKey=mall&targetVersion=v2.5.0
→ { "data": { "ready": false, "openBlockerBugs": 1, "verdict": "..." } }
```

### 7.3 缺陷管理

```http
GET  /api/v1/pm/bugs?projectKey=mall&status=OPEN&severity=P0
POST /api/v1/pm/bugs?projectKey=mall
Body: { "title", "severity", "foundBy", "foundVersion", "assignedTo",
        "requirementId", "description" }
GET  /api/v1/pm/bugs/{bugId}/trace
POST /api/v1/pm/bugs/{bugId}/link?changeEventId=xxx&linkType=FIX
```

---

## 8. 变更订阅

```http
GET    /api/v1/subscriptions
POST   /api/v1/subscriptions
Body: { "name", "workspaceId", "userId", "filter": {...}, "channel": "FEISHU", "webhookUrl" }
PUT    /api/v1/subscriptions/{id}?enabled=true
DELETE /api/v1/subscriptions/{id}
GET    /api/v1/subscriptions/logs?limit=50
```

---

## 9. 端到端追踪

```http
GET  /api/v1/projects/{key}/trace/requirement/{id}
GET  /api/v1/projects/{key}/trace/bug/{id}
GET  /api/v1/projects/{key}/trace/release/{version}
POST /api/v1/projects/{key}/trace/build?requirementId=1
GET  /api/v1/projects/{key}/trace
```

---

## 10. 通用

### 10.1 Dashboard

```http
GET /api/v1/dashboard/stats
GET /api/v1/dashboard/recent-releases
GET /api/v1/dashboard/trend
```

### 10.2 错误码

| 错误码 | 说明 |
| --- | --- |
| `0` | 成功 |
| `EVO-AUTH-401` | 未登录 |
| `EVO-AUTH-001` | API签名无效 |
| `EVO-BIZ-400` | 参数错误 |
| `EVO-BIZ-404` | 记录不存在 |
| `EVO-SYS-500` | 服务内部错误 |
