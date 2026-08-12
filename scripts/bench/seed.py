#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EvoTrace 事件摄入性能压测种子脚本（seed.py）
================================================================
批量生成 Envelope 事件 JSON，并并发 POST 到 /open-api/v1/events。

鉴权方式：
  - 请求头 X-EvoTrace-Api-Key    携带 API Key
  - 请求头 X-EvoTrace-Signature  对 "原始请求体(raw body)" 用 api-secret
                                 计算 HMAC-SHA256 并转 hex（小写）

说明：
  - 优先使用 Python3 标准库（urllib + threading + hmac + hashlib），
    不依赖任何第三方库，开箱即用。
  - 若希望更底层的并发控制，可通过 --concurrency 指定线程数。

用法示例：
  python3 scripts/bench/seed.py --profile small
  python3 scripts/bench/seed.py --events 5000 --concurrency 20 --json results/dev.json
  python3 scripts/bench/seed.py --base-url http://localhost:8080 \
      --api-key YOUR_KEY --api-secret YOUR_SECRET
"""

import argparse
import hashlib
import hmac
import json
import os
import queue
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from urllib import request as urlrequest
from urllib.error import HTTPError, URLError

# ---------------------------------------------------------------------------
# 常量与默认值
# ---------------------------------------------------------------------------
DEFAULT_BASE_URL = "http://localhost:8080"
ENDPOINT = "/open-api/v1/events"

# 事件类型轮换列表（按任务要求：CODE_COMMIT / DDL_CHANGE / CONFIG_CHANGE / API_CHANGE）
EVENT_TYPES = ["CODE_COMMIT", "DDL_CHANGE", "CONFIG_CHANGE", "API_CHANGE"]

# 事件来源（对齐 Envelope 的 EventSource 枚举）
EVENT_SOURCE = "OPEN_API"

# 协议版本（对齐 Envelope.CURRENT_VERSION）
PROTOCOL_VERSION = "v1"

# 样例数据池（用于生成"看似合理"的 payload）
BRANCHES = ["main", "develop", "release/2.5", "feature/evotrace-bench", "hotfix/ingest"]
AUTHORS = ["zhang.san", "li.si", "wang.wu", "zhao.liu", "chen.qi"]
MESSAGES = ["fix: 优化事件摄入链路", "feat: 新增演化追踪 API", "refactor: 重构比对逻辑",
            "docs: 更新接口文档", "test: 补充压测用例"]
FILES = ["src/main/java/io/evotrace/server/IngestionService.java",
         "src/main/resources/application.yml",
         "evotrace-ui/src/views/TimelineView.vue",
         "pom.xml",
         "docs/03-API接口文档.md"]


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def now_iso():
    """当前时间，ISO8601 格式（带 +00:00 时区偏移，与 OffsetDateTime 对齐）。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def hmac_sha256_hex(body, secret):
    """对 body 用 secret 计算 HMAC-SHA256 并返回 hex（与后端 SignatureVerifier.sign 对齐）。"""
    return hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()


def build_payload(event_type, idx):
    """按事件类型生成一份"看似合理"的样例 payload。"""
    branch = BRANCHES[idx % len(BRANCHES)]
    if event_type == "CODE_COMMIT":
        return {
            "repoUrl": "http://git.example.com/evotrace/evotrace-server.git",
            "branch": branch,
            "commitSha": hashlib.sha1(("bench-%d" % idx).encode()).hexdigest(),
            "authorName": AUTHORS[idx % len(AUTHORS)],
            "authorEmail": AUTHORS[idx % len(AUTHORS)] + "@example.com",
            "message": MESSAGES[idx % len(MESSAGES)],
            "files": [
                {"oldPath": None, "newPath": FILES[idx % len(FILES)],
                 "kind": "MODIFIED", "addLines": idx % 80, "delLines": idx % 30,
                 "diffBlobRef": None}
            ],
        }
    elif event_type == "DDL_CHANGE":
        return {
            "branch": branch,
            "database": "evotrace",
            "table": "change_event",
            "ddl": "ALTER TABLE change_event ADD COLUMN bench_col INT DEFAULT 0;",
            "authorName": AUTHORS[idx % len(AUTHORS)],
            "commitSha": hashlib.sha1(("bench-ddl-%d" % idx).encode()).hexdigest(),
        }
    elif event_type == "CONFIG_CHANGE":
        return {
            "branch": branch,
            "configFile": "application.yml",
            "key": "bench.ingest.threads",
            "oldValue": "10",
            "newValue": str(20 + (idx % 50)),
            "authorName": AUTHORS[idx % len(AUTHORS)],
        }
    else:  # API_CHANGE
        return {
            "branch": branch,
            "endpoint": "/open-api/v1/events",
            "method": "POST",
            "change": "ADDED",
            "summary": "新增事件摄入接口",
            "authorName": AUTHORS[idx % len(AUTHORS)],
        }


def build_envelope(idx, project_key, app_key):
    """生成一条完整 Envelope 事件（对齐 Envelope 记录结构）。"""
    event_type = EVENT_TYPES[idx % len(EVENT_TYPES)]
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "eventId": str(uuid.uuid4()),
        "projectKey": project_key,
        "appKey": app_key,
        "eventType": event_type,
        "occurredAt": now_iso(),
        "source": EVENT_SOURCE,
        "idempotencyKey": "bench:%s" % str(uuid.uuid4()),
        "payload": build_payload(event_type, idx),
        # blobRef 对本压测可选，默认不填
    }


# ---------------------------------------------------------------------------
# 发送器
# ---------------------------------------------------------------------------
class Sender:
    """负责对单个请求发送 HTTP POST，并记录耗时与结果。"""

    def __init__(self, base_url, api_key, api_secret):
        self.endpoint = base_url.rstrip("/") + ENDPOINT
        self.api_key = api_key
        self.api_secret = api_secret

    def send(self, envelope):
        """发送单条事件，返回 (耗时_ms, 是否成功)。"""
        body = json.dumps(envelope, ensure_ascii=False)
        signature = hmac_sha256_hex(body, self.api_secret)
        req = urlrequest.Request(
            self.endpoint,
            data=body.encode("utf-8"),
            method="POST",
            headers={
                "Content-Type": "application/json",
                "X-EvoTrace-Api-Key": self.api_key,
                "X-EvoTrace-Signature": signature,
            },
        )
        start = time.perf_counter()
        try:
            with urlrequest.urlopen(req, timeout=30) as resp:
                resp.read()  # 读取响应体，确保请求完成
                status = resp.status
            ok = status < 400
        except HTTPError as e:
            status = e.code
            ok = False
        except URLError:
            status = 0
            ok = False
        except Exception:
            status = 0
            ok = False
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return elapsed_ms, ok, status


def run_benchmark(args):
    """并发发送事件，返回统计结果 dict。"""
    sender = Sender(args.base_url, args.api_key, args.api_secret)

    # 任务队列：填入所有待发送的事件索引
    task_queue = queue.Queue()
    for i in range(args.events):
        task_queue.put(i)

    # 统计锁与共享状态
    lock = threading.Lock()
    total = args.events
    success = 0
    fail = 0
    latencies = []  # 所有成功的耗时（ms），用于计算吞吐与 P95

    def worker(worker_id):
        nonlocal success, fail
        while True:
            try:
                idx = task_queue.get_nowait()
            except queue.Empty:
                return
            envelope = build_envelope(idx, args.project_key, args.app_key)
            elapsed_ms, ok, status = sender.send(envelope)
            with lock:
                if ok:
                    success += 1
                    latencies.append(elapsed_ms)
                else:
                    fail += 1
            task_queue.task_done()
            # 可选控速：每次发送后休眠指定毫秒数
            if args.interval_ms and args.interval_ms > 0:
                time.sleep(args.interval_ms / 1000.0)

    start_wall = time.perf_counter()
    threads = [threading.Thread(target=worker, args=(i,), daemon=True)
               for i in range(args.concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall_sec = max(time.perf_counter() - start_wall, 1e-6)

    # 计算吞吐与 P95
    throughput = success / wall_sec
    if latencies:
        sorted_lat = sorted(latencies)
        idx_95 = int(0.95 * (len(sorted_lat) - 1))
        p95_ms = sorted_lat[idx_95]
        avg_ms = sum(sorted_lat) / len(sorted_lat)
    else:
        p95_ms = 0.0
        avg_ms = 0.0

    return {
        "baseUrl": args.base_url,
        "projectKey": args.project_key,
        "appKey": args.app_key,
        "total": total,
        "success": success,
        "fail": fail,
        "wallSec": round(wall_sec, 3),
        "throughputEventsPerSec": round(throughput, 2),
        "p95Ms": round(p95_ms, 2),
        "avgMs": round(avg_ms, 2),
        "errorRate": round(fail / total if total else 0.0, 4),
        "timestamp": now_iso(),
    }


def print_stats(stats):
    """将统计结果打印到 stdout。"""
    print("=" * 60)
    print("EvoTrace 事件摄入压测统计")
    print("=" * 60)
    print("目标地址      : %s" % stats["baseUrl"])
    print("项目          : %s / %s" % (stats["projectKey"], stats["appKey"]))
    print("事件总量      : %d" % stats["total"])
    print("成功数        : %d" % stats["success"])
    print("失败数        : %d" % stats["fail"])
    print("错误率        : %.2f%%" % (stats["errorRate"] * 100))
    print("耗时(墙钟)    : %.3f s" % stats["wallSec"])
    print("吞吐          : %.2f events/s" % stats["throughputEventsPerSec"])
    print("P95 延迟      : %.2f ms" % stats["p95Ms"])
    print("平均延迟      : %.2f ms" % stats["avgMs"])
    print("=" * 60)


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="EvoTrace 事件摄入压测脚本：批量生成 Envelope 并并发 POST。",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="服务基础地址，如 http://localhost:8080")
    parser.add_argument("--api-key", required=True, help="API Key（放入 X-EvoTrace-Api-Key）")
    parser.add_argument("--api-secret", required=True,
                        help="API Secret（用于计算 HMAC-SHA256 签名）")
    parser.add_argument("--project-key", default="evotrace", help="项目标识")
    parser.add_argument("--app-key", default="default", help="应用标识")
    parser.add_argument("--events", type=int, default=1000, help="事件总量")
    parser.add_argument("--concurrency", type=int, default=10, help="并发线程数")
    parser.add_argument("--interval-ms", type=float, default=None,
                        help="可选发送间隔（毫秒），用于控速；None 表示不控速")
    parser.add_argument("--profile", default=None,
                        help="从 profiles.yaml 读取档位（small/medium/large），"
                             "覆盖 --concurrency/--events/--duration")
    parser.add_argument("--json", default=None,
                        help="将统计结果写入 JSON 文件（供 report.py 汇总）")
    return parser.parse_args(argv)


def load_profile(name):
    """从同目录 profiles.yaml 读取档位，返回 (concurrency, events, duration)。"""
    profiles_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "profiles.yaml")
    try:
        import yaml  # PyYAML 为可选依赖；未安装时回退到手工解析
    except ImportError:
        return _parse_profiles_fallback(profiles_path, name)
    with open(profiles_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if name not in data:
        raise SystemExit("未找到 profile: %s（可选：small/medium/large）" % name)
    return (data[name]["concurrency"], data[name]["events"], data[name]["duration"])


def _parse_profiles_fallback(path, name):
    """无 PyYAML 时的简易解析（仅支持本文件结构）。"""
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    # 找到形如 "name:" 的块
    block = text.split(name + ":")[-1]
    block = block.split("\n\n")[0]
    concurrency = events = duration = None
    for line in block.splitlines():
        line = line.strip()
        if line.startswith("concurrency:"):
            concurrency = int(line.split(":", 1)[1].split("#")[0].strip())
        elif line.startswith("events:"):
            events = int(line.split(":", 1)[1].split("#")[0].strip())
        elif line.startswith("duration:"):
            duration = int(line.split(":", 1)[1].split("#")[0].strip())
    if concurrency is None or events is None or duration is None:
        raise SystemExit("未找到 profile: %s（可选：small/medium/large）" % name)
    return concurrency, events, duration


def main(argv=None):
    args = parse_args(argv)

    # 若指定 profile，则覆盖并发/事件量/时长
    if args.profile:
        concurrency, events, duration = load_profile(args.profile)
        args.concurrency = concurrency
        args.events = events
        print("使用 profile[%s]: 并发=%d, 事件量=%d, 时长=%d s"
              % (args.profile, concurrency, events, duration))
        if args.interval_ms is not None:
            print("注意：已同时指定 --interval-ms，将按实际发送间隔控速。")

    stats = run_benchmark(args)
    print_stats(stats)

    # 可选：写入 JSON 结果文件
    if args.json:
        out = args.json
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            json.dump(stats, f, ensure_ascii=False, indent=2)
        print("统计结果已写入：%s" % out)

    # 退出码：若有失败则非 0，便于 CI 判断
    return 1 if stats["fail"] > 0 else 0


if __name__ == "__main__":
    sys.exit(main())