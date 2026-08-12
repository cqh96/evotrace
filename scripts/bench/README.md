# EvoTrace 性能压测（基准测试）脚本

本目录提供 EvoTrace 平台事件摄入与核心链路（ingest / compare / timeline）的性能压测脚本与阈值基线。

## 目录结构

```
scripts/bench/
├── thresholds.yaml   # 阈值基线（按 dev/staging/prod 区分）
├── profiles.yaml     # 三档压测档位（small/medium/large）
├── seed.py           # 事件生成 + 并发发送脚本（Python3 标准库实现）
├── ingest.js         # k6 事件摄入压测骨架（可选）
├── report.py         # 结果汇总 + 阈值比对 + 生成 result.md
└── README.md         # 本说明
```

## 1. 背景与鉴权

EvoTrace 通过 `POST /open-api/v1/events` 接收 Envelope 事件（JSON，含 `eventId / projectKey / appKey / eventType / occurredAt / source / idempotencyKey / payload`）。

请求需携带两个请求头完成鉴权：

| 请求头 | 说明 |
| --- | --- |
| `X-EvoTrace-Api-Key` | API Key |
| `X-EvoTrace-Signature` | 对**原始请求体**用 `api-secret` 计算 HMAC-SHA256 并转 hex |

签名算法与后端 `SignatureVerifier` 完全一致：

```text
X-EvoTrace-Signature = hex( HMAC-SHA256( secret = api_secret, message = rawBody ) )
```

## 2. 安装依赖

- **seed.py / report.py**：仅依赖 Python3 标准库，无需安装任何第三方包。
  - 若使用 `--profile` 读取 `profiles.yaml`，推荐安装 `PyYAML`（可选，未安装时脚本会回退到简易解析）：
    ```bash
    pip3 install pyyaml
    ```
- **ingest.js**：需要安装 [k6](https://grafana.com/docs/k6/)（可选，不强制）。
  ```bash
  brew install k6        # macOS
  ```

## 3. 运行 seed.py（事件摄入压测）

最基本的运行方式（需提供 API Key 与 Secret）：

```bash
python3 scripts/bench/seed.py \
    --base-url http://localhost:8080 \
    --api-key YOUR_KEY \
    --api-secret YOUR_SECRET \
    --events 1000 --concurrency 10
```

### 常用参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--base-url` | `http://localhost:8080` | 服务基础地址 |
| `--api-key` | 必填 | API Key |
| `--api-secret` | 必填 | API Secret（用于 HMAC 签名） |
| `--project-key` | `evotrace` | 项目标识 |
| `--app-key` | `default` | 应用标识 |
| `--events` | `1000` | 事件总量 |
| `--concurrency` | `10` | 并发线程数 |
| `--interval-ms` | 无 | 可选，每次发送后的间隔（毫秒），用于控速 |
| `--profile` | 无 | 读取 `profiles.yaml` 档位（small/medium/large） |
| `--json` | 无 | 将统计结果写入 JSON（供 report.py 汇总） |

### 使用 profile 档位

```bash
python3 scripts/bench/seed.py \
    --api-key YOUR_KEY --api-secret YOUR_SECRET \
    --profile medium --json results/medium.json
```

### HMAC 签名说明

`seed.py` 对每条事件生成的 JSON 字符串（`json.dumps` 后的原始字节）用 `api-secret` 做 HMAC-SHA256 并取 hex，放入 `X-EvoTrace-Signature`。请确保 `--api-secret` 与后端创建凭证时使用的 Secret 一致，否则会被 `SignatureVerifier` 拒绝。

### 输出

脚本会打印成功数、失败数、吞吐（events/s）、P95 延迟；`--json` 时写入结果文件，例如：

```json
{
  "total": 50000,
  "success": 50000,
  "fail": 0,
  "throughputEventsPerSec": 621.3,
  "p95Ms": 812.0,
  "errorRate": 0.0
}
```

## 4. 运行 ingest.js（k6 压测）

k6 需要对**原始 body 字符串**做 HMAC 签名，脚本已通过 `crypto.hmac` 处理。运行：

```bash
k6 run \
    -e BASE_URL=http://localhost:8080 \
    -e API_KEY=YOUR_KEY \
    -e API_SECRET=YOUR_SECRET \
    -e PROJECT_KEY=evotrace \
    -e APP_KEY=default \
    -e VUS=100 \
    -e DURATION=120s \
    -e THRESHOLD_EVENTS_PER_SEC=500 \
    -e THRESHOLD_P95_MS=2000 \
    scripts/bench/ingest.js
```

> 提示：若你更习惯 Python 方案的统计与报告联动，推荐直接使用 `seed.py`（k6 为可选骨架）。

## 5. 查看报告（report.py）

先运行压测产出 JSON 结果，再用 `report.py` 汇总并与阈值比对：

```bash
# 汇总单个结果
python3 scripts/bench/report.py --input results/medium.json --env staging

# 汇总目录下所有结果
python3 scripts/bench/report.py --dir results --env prod --output results/result.md
```

`report.py` 会：

- 汇总吞吐 / P95 延迟 / 错误率；
- 与 `thresholds.yaml` 中对应环境的阈值逐项比对，输出 `PASS / FAIL / NA` 结果表；
- 生成 `result.md` 报告文件（默认 `results/result.md`）。

> 若结果 JSON 缺少 compare/timeline 等指标字段，对应项会显示 `NA`（不影响整体判定）。

## 6. 阈值如何调整

编辑 `scripts/bench/thresholds.yaml`，按环境（dev/staging/prod）修改对应数值即可：

```yaml
dev:
  ingest:
    throughput:
      eventsPerSec: { min: 500 }   # 吞吐下限
    p95Ms: { max: 2000 }           # 摄入 P95 上限
  webhookToVisibleMs: { max: 10000 }
  compare:    { p95Ms: { max: 5000 } }
  timeline:   { p95Ms: { max: 1000 } }
```

保存后重跑 `report.py` 即生效，无需改动代码。

## 7. Profile 用法

`profiles.yaml` 定义三档压测规模：

| 档位 | 并发 | 事件量 | 时长 |
| --- | --- | --- | --- |
| small | 20 | 5k | 30s |
| medium | 100 | 50k | 120s |
| large | 500 | 200k | 300s |

通过 `--profile small|medium|large` 引用；也可手动用 `--concurrency / --events / --duration` 覆盖。

## 8. 一次性完整流程

```bash
# 1) 安装可选依赖
pip3 install pyyaml

# 2) 跑中档压测并输出 JSON
python3 scripts/bench/seed.py --api-key YOUR_KEY --api-secret YOUR_SECRET \
    --profile medium --json results/medium.json

# 3) 汇总并生成报告
python3 scripts/bench/report.py --input results/medium.json --env staging \
    --output results/result.md
```