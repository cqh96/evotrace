/*
 * EvoTrace 事件摄入压测脚本（k6 骨架）
 * =====================================================================
 * 用 k6 模拟事件摄入压测，POST 到 /open-api/v1/events。
 *
 * k6 与 HMAC 签名的说明：
 *   EvoTrace 要求对"原始请求体(raw body)"计算 HMAC-SHA256 签名并放入
 *   X-EvoTrace-Signature 头。k6 的 http.post 在传入 JSON 对象时会对 body
 *   做序列化，因此为保证签名与实际上行字节一致，这里用 __ENV 环境变量
 *   传入原始 body 字符串，并直接用 crypto.hmac 对该字符串签名。
 *
 * 运行方式：
 *   k6 run \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e API_KEY=YOUR_KEY \
 *     -e API_SECRET=YOUR_SECRET \
 *     -e VUS=100 -e DURATION=120s \
 *     scripts/bench/ingest.js
 *
 * 说明：
 *   - 若觉得 k6 与平台签名验证较复杂，可退化为直接调用 seed.py：
 *       python3 scripts/bench/seed.py --profile medium \
 *           --api-key YOUR_KEY --api-secret YOUR_SECRET
 *   - 本脚本仍提供可运行的 k6 骨架，两种方式结果都可被 report.py 汇总。
 */

import http from "k6/http";
import crypto from "k6/crypto";
import { check } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";

// ---------------------------------------------------------------------------
// 自定义指标（便于与 thresholds.yaml 对齐）
// ---------------------------------------------------------------------------
const ingestLatency = new Trend("ingest.p95Ms", true);   // 摄入延迟（毫秒）
const successRate  = new Rate("ingest.successRate");     // 成功率
const eventCounter = new Counter("ingest.events");       // 事件计数

// ---------------------------------------------------------------------------
// 从环境变量读取压测参数（对应 profiles.yaml 的 small/medium/large）
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API_KEY  = __ENV.API_KEY  || "";
const API_SECRET = __ENV.API_SECRET || "";
const VUS      = __ENV.VUS ? Number(__ENV.VUS) : 10;
const DURATION = __ENV.DURATION || "30s";

const ENDPOINT = "/open-api/v1/events";

// ---------------------------------------------------------------------------
// options：并发、时长、阈值（引用 ingest.throughput / P95）
//   通过环境变量注入阈值，便于与 thresholds.yaml 联动
// ---------------------------------------------------------------------------
const THRESHOLD_EVENTS_PER_SEC = __ENV.THRESHOLD_EVENTS_PER_SEC ? Number(__ENV.THRESHOLD_EVENTS_PER_SEC) : 500;
const THRESHOLD_P95_MS         = __ENV.THRESHOLD_P95_MS ? Number(__ENV.THRESHOLD_P95_MS) : 2000;

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        // 吞吐：>= 500 events/s（对应 ingest.throughput.eventsPerSec.min）
        [ingestLatency.name]: [
            `p(95)<=${THRESHOLD_P95_MS}`,                       // 摄入 P95 延迟上限
        ],
        "http_reqs": [
            `rate>=${THRESHOLD_EVENTS_PER_SEC}`,                // 请求速率下限，近似吞吐
        ],
        [successRate.name]: ["rate>=0.99"],                     // 成功率 >= 99%
    },
};

// ---------------------------------------------------------------------------
// 生成一条样例 Envelope 的原始 body 字符串
// ---------------------------------------------------------------------------
let seq = 0;
function buildBody() {
    seq += 1;
    const eventTypes = ["CODE_COMMIT", "DDL_CHANGE", "CONFIG_CHANGE", "API_CHANGE"];
    const eventType = eventTypes[seq % eventTypes.length];
    const envelope = {
        protocolVersion: "v1",
        eventId: crypto.uuidv4(),
        projectKey: __ENV.PROJECT_KEY || "evotrace",
        appKey: __ENV.APP_KEY || "default",
        eventType: eventType,
        occurredAt: new Date().toISOString(),
        source: "OPEN_API",
        idempotencyKey: "bench:" + crypto.uuidv4(),
        payload: {
            branch: "main",
            commitSha: crypto.randomBytes(20).map((b) => b.toString(16).padStart(2, "0")).join(""),
            authorName: "bench.user",
            message: "benchmark event",
            files: [{ oldPath: null, newPath: "src/main/java/ingest.java",
                      kind: "MODIFIED", addLines: 10, delLines: 2, diffBlobRef: null }],
        },
    };
    return JSON.stringify(envelope);
}

// ---------------------------------------------------------------------------
// 默认迭代入口
// ---------------------------------------------------------------------------
export default function () {
    const body = buildBody();

    // 对原始 body 计算 HMAC-SHA256 签名
    const signature = crypto.hmac("sha256", API_SECRET, body, "hex");

    const params = {
        headers: {
            "Content-Type": "application/json",
            "X-EvoTrace-Api-Key": API_KEY,
            "X-EvoTrace-Signature": signature,
        },
    };

    const started = Date.now();
    const res = http.post(BASE_URL + ENDPOINT, body, params);
    const elapsedMs = Date.now() - started;

    const ok = check(res, {
        "status 是 2xx": (r) => r.status >= 200 && r.status < 300,
    });

    ingestLatency.add(elapsedMs);
    successRate.add(ok);
    eventCounter.add(1);
}