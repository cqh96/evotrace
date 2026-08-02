#!/usr/bin/env bash
# =============================================================
# EvoTrace 一键部署: 本机构建 → 腾讯云服务器
#
# 用法:
#   ./deploy.sh                                # 交互输入 SSH 密码
#   EVOTRACE_DEPLOY_PASSWORD=xxx ./deploy.sh   # 环境变量提供密码(推荐 CI)
#   ./deploy.sh --skip-build                   # 跳过构建,复用现有产物
#   ./deploy.sh --server IP --user ubuntu      # 覆盖默认服务器
#
# 前提: 本机 Maven 3.9+ / Node 20+; 服务器端已按部署手册初始化。
# =============================================================
set -euo pipefail

# ---- 默认配置(可用环境变量覆盖) ----
SERVER="${EVOTRACE_DEPLOY_HOST:-43.155.130.69}"
USER="${EVOTRACE_DEPLOY_USER:-ubuntu}"
SKIP_BUILD=0

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/evotrace-server/target/evotrace-server-0.1.0-SNAPSHOT.jar"
DIST="$ROOT/evotrace-ui/dist"
REMOTE_JAR_DIR=/data/evotrace
REMOTE_WWW=/opt/1panel/www/sites/evotrace/index
KNOWN_HOSTS=/tmp/evotrace_known_hosts

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --server)     SERVER="$2"; shift 2 ;;
    --user)       USER="$2"; shift 2 ;;
    -h|--help)    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1 (见 --help)"; exit 1 ;;
  esac
done

# ---- 密码: 优先环境变量,否则交互输入 ----
if [[ -z "${EVOTRACE_DEPLOY_PASSWORD:-}" ]]; then
  read -r -s -p "[部署] SSH 密码 ($USER@$SERVER): " PASSWORD; echo
else
  PASSWORD="$EVOTRACE_DEPLOY_PASSWORD"
fi
export PASSWORD

# ---- askpass 辅助: 免 expect,密码特殊字符无转义问题 ----
ASKPASS="$(mktemp /tmp/evotrace_askpass.XXXXXX)"
printf '#!/bin/bash\necho "$PASSWORD"\n' > "$ASKPASS"
chmod 700 "$ASKPASS"
export SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force
trap 'rm -f "$ASKPASS"' EXIT

SSH_ARGS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile="$KNOWN_HOSTS" \
          -o ConnectTimeout=15 -o LogLevel=ERROR)
say()  { printf '\033[1;34m[部署]\033[0m %s\n' "$*"; }
remote() { ssh "${SSH_ARGS[@]}" "$USER@$SERVER" "$1"; }

# ---- 1. 构建 ----
if [[ $SKIP_BUILD -eq 0 ]]; then
  say "构建后端 jar (mvn install) ..."
  (cd "$ROOT" && mvn -T 1C install -DskipTests -q)
  say "构建前端 dist (npm run build) ..."
  (cd "$ROOT/evotrace-ui" && npm run build >/dev/null)
else
  say "跳过构建 (--skip-build)"
fi
[[ -f "$JAR" ]] || { echo "[部署] 缺少后端 jar: $JAR"; exit 1; }
[[ -f "$DIST/index.html" ]] || { echo "[部署] 缺少前端 dist: $DIST"; exit 1; }

# ---- 2. 上传 ----
say "上传后端 jar -> /tmp/evotrace-server.jar ..."
scp "${SSH_ARGS[@]}" "$JAR" "$USER@$SERVER:/tmp/evotrace-server.jar"
say "上传前端 dist -> /tmp/evotrace-dist/ ..."
rm -rf /tmp/evotrace-dist && mkdir -p /tmp/evotrace-dist
scp -r "${SSH_ARGS[@]}" "$DIST"/. "$USER@$SERVER:/tmp/evotrace-dist/"

# ---- 3. 安装 + 重启 ----
say "安装产物并重启后端 (systemctl) ..."
remote "sudo install -o ubuntu -g ubuntu -m 644 /tmp/evotrace-server.jar $REMOTE_JAR_DIR/evotrace-server.jar \
        && sudo rm -rf $REMOTE_WWW/* \
        && sudo cp -r /tmp/evotrace-dist/* $REMOTE_WWW/ \
        && sudo systemctl restart evotrace-server \
        && echo INSTALLED_OK"

# ---- 4. 健康检查 (公网轮询) ----
say "等待后端就绪 (最长 180s) ..."
for _ in $(seq 1 60); do
  sleep 3
  if HEALTH=$(curl -s -m 5 "http://$SERVER/actuator/health"); then
    if [[ "$HEALTH" == *'"status":"UP"'* ]]; then
      say "后端就绪: $HEALTH"
      say "✅ 部署完成 -> http://$SERVER  (admin/admin123)"
      exit 0
    fi
  fi
  printf '.'
done
echo
echo "[部署] 超时: 后端未就绪,请检查: journalctl -u evotrace-server -f"
exit 1
