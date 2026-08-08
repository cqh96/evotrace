#!/usr/bin/env bash
# =============================================================
# EvoTrace 前端快速部署: 仅构建并上传前端 dist,不重启后端
#
# 用法:
#   ./deploy-fe.sh                              # 交互输入 SSH 密码
#   EVOTRACE_DEPLOY_PASSWORD=xxx ./deploy-fe.sh # 环境变量提供密码(推荐 CI)
#   ./deploy-fe.sh --server IP --user ubuntu    # 覆盖默认服务器
# =============================================================
set -euo pipefail

SERVER="${EVOTRACE_DEPLOY_HOST:-43.155.130.69}"
USER="${EVOTRACE_DEPLOY_USER:-ubuntu}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/evotrace-ui/dist"
REMOTE_WWW=/opt/1panel/www/sites/evotrace/index
KNOWN_HOSTS=/tmp/evotrace_known_hosts

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server)     SERVER="$2"; shift 2 ;;
    --user)       USER="$2"; shift 2 ;;
    -h|--help)    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $1 (见 --help)"; exit 1 ;;
  esac
done

# ---- 密码: 优先环境变量,否则交互输入 ----
if [[ -z "${EVOTRACE_DEPLOY_PASSWORD:-}" ]]; then
  read -r -s -p "[前端部署] SSH 密码 ($USER@$SERVER): " PASSWORD; echo
else
  PASSWORD="$EVOTRACE_DEPLOY_PASSWORD"
fi
export PASSWORD

ASKPASS="$(mktemp /tmp/evotrace_askpass.XXXXXX)"
printf '#!/bin/bash\necho "$PASSWORD"\n' > "$ASKPASS"
chmod 700 "$ASKPASS"
export SSH_ASKPASS="$ASKPASS" SSH_ASKPASS_REQUIRE=force
trap 'rm -f "$ASKPASS"' EXIT

SSH_ARGS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile="$KNOWN_HOSTS" \
          -o ConnectTimeout=15 -o LogLevel=ERROR)
say()  { printf '\033[1;34m[前端部署]\033[0m %s\n' "$*"; }
remote() { ssh "${SSH_ARGS[@]}" "$USER@$SERVER" "$1"; }

# ---- 1. 构建前端 ----
say "构建前端 dist (npm run build) ..."
(cd "$ROOT/evotrace-ui" && npm run build >/dev/null)
[[ -f "$DIST/index.html" ]] || { echo "[前端部署] 缺少前端 dist: $DIST"; exit 1; }

# ---- 2. 上传 ----
say "上传前端 dist -> /tmp/evotrace-dist/ ..."
rm -rf /tmp/evotrace-dist && mkdir -p /tmp/evotrace-dist
scp -r "${SSH_ARGS[@]}" "$DIST"/. "$USER@$SERVER:/tmp/evotrace-dist/"

# ---- 3. 安装(仅替换静态资源,不重启后端) ----
say "安装前端资源到 $REMOTE_WWW ..."
remote "sudo rm -rf $REMOTE_WWW/* \
        && sudo cp -r /tmp/evotrace-dist/* $REMOTE_WWW/ \
        && echo FE_INSTALLED_OK"

# ---- 4. 校验 ----
say "校验站点可访问 ..."
if curl -s -m 8 -o /dev/null -w "%{http_code}" "http://$SERVER/" | grep -q 200; then
  say "✅ 前端部署完成 -> http://$SERVER"
  exit 0
fi

echo "[前端部署] 站点未返回 200,请检查 nginx 配置: nginx -t && systemctl reload nginx"
exit 1