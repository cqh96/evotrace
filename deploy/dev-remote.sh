#!/usr/bin/env bash
# EvoTrace 本地前端直连远程服务 一键启动脚本
set -euo pipefail

REMOTE_HOST="43.155.130.69"
REMOTE_USER="ubuntu"
# 密码从环境变量读取，避免把生产密钥提交到公开仓库
REMOTE_PASS="${EVOTRACE_REMOTE_PASS:-}"
LOCAL_PORT="18080"
FRONTEND_PORT="5173"
REMOTE_PORT="8080"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_DIR="$PROJECT_DIR/evotrace-ui"
TUNNEL_PID_FILE="/tmp/evotrace_tunnel.pid"
FRONT_PID_FILE="/tmp/evotrace_front.pid"
SSH_OPTS=(-o StrictHostKeyChecking=no -o ServerAliveInterval=30 -o ServerAliveCountMax=3)

log()  { echo -e "\033[1;36m[EvoTrace]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; }

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    err "缺少依赖命令: $1（请先安装，如 brew install $1）"
    exit 1
  fi
}

stop_all() {
  for f in "$TUNNEL_PID_FILE" "$FRONT_PID_FILE"; do
    if [[ -f "$f" ]]; then
      local pid
      pid="$(cat "$f")"
      if kill -0 "$pid" 2>/dev/null; then
        log "停止进程 PID=$pid"
        kill "$pid" 2>/dev/null || true
      fi
      rm -f "$f"
    fi
  done
  log "已停止隧道与前端。"
}

if [[ "${1:-}" == "--stop" ]]; then
  stop_all
  exit 0
fi

require_cmd sshpass
require_cmd ssh
require_cmd npx

[[ -n "$REMOTE_PASS" ]] || { err "缺少 SSH 密码，请设置环境变量 EVOTRACE_REMOTE_PASS 后重试"; exit 1; }

# 1. 检查并建立 SSH 隧道
if [[ -f "$TUNNEL_PID_FILE" ]] && kill -0 "$(cat "$TUNNEL_PID_FILE")" 2>/dev/null; then
  log "SSH 隧道已在运行，跳过建立。"
else
  log "建立 SSH 隧道：本地 :$LOCAL_PORT -> $REMOTE_HOST:$REMOTE_PORT"
  sshpass -p "$REMOTE_PASS" ssh "${SSH_OPTS[@]}" -N \
    -L "$LOCAL_PORT:127.0.0.1:$REMOTE_PORT" \
    "$REMOTE_USER@$REMOTE_HOST" &
  TUNNEL_PID=$!
  echo "$TUNNEL_PID" > "$TUNNEL_PID_FILE"
  for _ in $(seq 1 15); do
    if curl -sf -m 2 "http://localhost:$LOCAL_PORT/actuator/health" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  if ! curl -sf -m 2 "http://localhost:$LOCAL_PORT/actuator/health" >/dev/null 2>&1; then
    err "SSH 隧道未就绪，请检查远程地址/账号/端口。"
    kill "$TUNNEL_PID" 2>/dev/null || true
    rm -f "$TUNNEL_PID_FILE"
    exit 1
  fi
  log "SSH 隧道就绪：本地 :$LOCAL_PORT 已连通远程服务。"
fi

# 2. 启动前端
if [[ -f "$FRONT_PID_FILE" ]] && kill -0 "$(cat "$FRONT_PID_FILE")" 2>/dev/null; then
  log "前端已在运行，跳过启动。"
else
  log "启动前端（proxy 指向 http://localhost:$LOCAL_PORT）"
  cd "$UI_DIR"
  EVOTRACE_PROXY_TARGET="http://localhost:$LOCAL_PORT" npx vite --port "$FRONTEND_PORT" &
  FRONT_PID=$!
  echo "$FRONT_PID" > "$FRONT_PID_FILE"
  log "前端已启动。"
fi

log "完成！请打开浏览器访问 http://localhost:$FRONTEND_PORT"
log "默认账号：admin / admin123"
log "注意：在侧边栏项目选择器中请选择 maidao_merchant 或 maidao_admin"
log "停止：./deploy/dev-remote.sh --stop"
