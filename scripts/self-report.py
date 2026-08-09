#!/usr/bin/env python3
"""EvoTrace 自监控上报：把本地 git 提交增量上报到自部署的 EvoTrace 实例。

用法:
  python3 scripts/self-report.py            # 从 state 文件上次位置起增量上报
  python3 scripts/self-report.py --from HEAD~20   # 从指定提交起上报（含历史）
  python3 scripts/self-report.py --init           # 把 HEAD 标记为已上报（不真正上报）

依赖: 仅标准库（urllib / hashlib / hmac / subprocess）。
配置: 优先环境变量 EVOTRACE_*，否则读取 scripts/.self-monitor.env（已 gitignore）。
"""
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone, timedelta

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE_FILE = os.path.join(REPO, ".evotrace-self-state")

# ---- 配置解析 ----
def load_env(path):
    env = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env

def cfg(key, default=None):
    return os.environ.get(key) or local.get(key) or default

local = load_env(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".self-monitor.env"))

SERVER_URL = cfg("EVOTRACE_SERVER_URL", "http://43.155.130.69").rstrip("/")
PROJECT_KEY = cfg("EVOTRACE_SELF_PROJECT", "evotrace")
APP_KEY     = cfg("EVOTRACE_SELF_APP", "evotrace-server")
API_KEY     = cfg("EVOTRACE_SELF_API_KEY")
API_SECRET  = cfg("EVOTRACE_SELF_API_SECRET")
BRANCH      = cfg("EVOTRACE_SELF_BRANCH") or "main"
REPO_URL    = cfg("EVOTRACE_SELF_REPO_URL", "https://github.com/cqh96/evotrace")

if not API_KEY or not API_SECRET:
    print("[self-report] 缺少凭证，请配置 scripts/.self-monitor.env 或环境变量 EVOTRACE_SELF_API_KEY/EVOTRACE_SELF_API_SECRET")
    sys.exit(2)

def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, check=True).stdout

def read_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except FileNotFoundError:
        return ""

def write_state(sha):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        f.write(sha + "\n")

def collect_commits(from_sha, to_sha="HEAD"):
    """返回从 from_sha(不含) 到 to_sha 的提交列表（旧→新）。"""
    if from_sha:
        try:
            return git("log", "--reverse", "--format=%H", f"{from_sha}..{to_sha}").split()
        except subprocess.CalledProcessError:
            # from_sha 不在当前历史，退化为全量
            return git("log", "--reverse", "--format=%H", to_sha).split()
    return git("log", "--reverse", "--format=%H", to_sha).split()

def build_files(sha):
    """用 diff-tree 解析提交的变更文件（kind + 行数）。"""
    files = []
    # name-status: <kind>\t<path>
    ns = git("diff-tree", "--no-commit-id", "--name-status", "-r", sha).splitlines()
    # numstat: <add>\t<del>\t<path>
    num = {}
    for line in git("diff-tree", "--no-commit-id", "--numstat", "-r", sha).splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            num[parts[2]] = parts[:2]
    for line in ns:
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        kind, path = parts[0], parts[1]
        add = del_ = 0
        if path in num:
            a, d = num[path]
            if a.isdigit():
                add = int(a)
            if d.isdigit():
                del_ = int(d)
        if kind == "A":
            f = {"oldPath": None, "newPath": path, "kind": "ADDED", "addLines": add, "delLines": del_, "diffBlobRef": None}
        elif kind == "D":
            f = {"oldPath": path, "newPath": None, "kind": "DELETED", "addLines": add, "delLines": del_, "diffBlobRef": None}
        else:  # M / R / C
            f = {"oldPath": path, "newPath": path, "kind": "MODIFIED", "addLines": add, "delLines": del_, "diffBlobRef": None}
        files.append(f)
    return files

def build_envelope(sha):
    meta = git("log", "-1", "--format=%an%x1f%ae%x1f%s%x1f%aI", sha).strip()
    parts = meta.split("\x1f")
    author_name = parts[0] if len(parts) > 0 else "unknown"
    author_email = parts[1] if len(parts) > 1 else ""
    message = parts[2] if len(parts) > 2 else ""
    occurred = parts[3] if len(parts) > 3 else datetime.now(timezone(timedelta(hours=8))).isoformat()
    return {
        "protocolVersion": "v1",
        "eventId": str(uuid.uuid4()),
        "projectKey": PROJECT_KEY,
        "appKey": APP_KEY,
        "eventType": "CODE_COMMIT",
        "occurredAt": occurred if occurred else now.isoformat(),
        "source": "OPEN_API",
        "idempotencyKey": f"selftrack:{PROJECT_KEY}:{sha}",
        "payload": {
            "repoUrl": REPO_URL,
            "branch": BRANCH,
            "commitSha": sha,
            "parentShas": [],
            "authorName": author_name or "unknown",
            "authorEmail": author_email or "",
            "message": message or "",
            "files": build_files(sha),
        },
    }

def post(envelope):
    body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    signature = hmac.new(API_SECRET.encode("utf-8"), body, hashlib.sha256).hexdigest()
    req = urllib_request.Request(
        SERVER_URL + "/open-api/v1/events",
        data=body, method="POST",
        headers={"X-EvoTrace-Api-Key": API_KEY, "X-EvoTrace-Signature": signature,
                 "Content-Type": "application/json"},
    )
    with urllib_request.urlopen(req, timeout=30) as resp:
        return resp.status, resp.read().decode("utf-8")

import urllib.request as urllib_request
import urllib.error

def main():
    args = sys.argv[1:]
    if "--init" in args:
        head = git("rev-parse", "HEAD").strip()
        write_state(head)
        print(f"[self-report] 标记 HEAD {head} 为已上报（不发送）")
        return 0

    from_arg = None
    if "--from" in args:
        i = args.index("--from")
        from_arg = args[i + 1]

    last = from_arg or read_state()
    commits = collect_commits(last)
    if not commits:
        print("[self-report] 没有新提交")
        return 0

    ok = 0
    for sha in commits:
        try:
            status, resp = post(build_envelope(sha))
            if status == 200:
                ok += 1
                print(f"[self-report] ✓ {sha[0:8]} {status}")
            else:
                print(f"[self-report] ✗ {sha[0:8]} HTTP {status}: {resp}")
        except urllib.error.HTTPError as e:
            print(f"[self-report] ✗ {sha[0:8]} HTTP {e.code}: {e.read().decode('utf-8')}")
        except Exception as e:
            print(f"[self-report] ✗ {sha[0:8]} {repr(e)}")
        write_state(sha)

    print(f"[self-report] 完成，共上报 {ok}/{len(commits)} 个提交")
    return 0

if __name__ == "__main__":
    sys.exit(main())