#!/usr/bin/env bash
# 生成 deploy/.env：全部密钥随机生成（base64url / 字母数字，避免 '+' 导致 OAuth invalid_client）。
# 已存在则拒绝覆盖（防止误轮换导致数据库口令与卷内数据不一致）。
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  echo "deploy/.env 已存在，未覆盖。需要轮换请参考 deploy/README.md。" >&2
  exit 1
fi

b64url() { openssl rand -base64 "$1" | tr '+/' '-_' | tr -d '=\n'; }
alnum() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }

umask 077
{
  echo "# 由 gen-secrets.sh 生成于 $(date -u +%Y-%m-%dT%H:%M:%SZ)。不要提交到仓库，不要发到聊天/邮件。"
  echo "MOLD_VERSION=$(git -C .. rev-parse --short HEAD 2>/dev/null || echo dev)"
  echo
  echo "# 对外监听（默认只本机；上线前面接 TLS 网关，或改成 0.0.0.0）"
  echo "MOLD_HTTP_BIND=127.0.0.1"
  echo "MOLD_HTTP_PORT=8080"
  echo
  echo "MYSQL_ROOT_PASSWORD=$(alnum 32)"
  echo "MOLD_DB_USER=mold_app"
  echo "MOLD_DB_PASSWORD=$(alnum 32)"
  echo "MOLD_REDIS_PASSWORD=$(alnum 32)"
  echo "MOLD_AUTH_CLIENT_SECRET=$(b64url 48 | cut -c1-48)"
  echo "MOLD_ENCODE_KEY=$(alnum 16)"
  echo "MOLD_JASYPT_PASSWORD=$(alnum 32)"
  echo
  echo "# 首个 OWNER 账号：仅在库里没有在职 OWNER 时创建一次，首次登录强制改密。"
  echo "# 登录成功并改密后，可删除下面两行。"
  echo "MOLD_BOOTSTRAP_OWNER_LOGIN=owner"
  echo "MOLD_BOOTSTRAP_OWNER_PASSWORD=$(alnum 20)"
} > .env
chmod 600 .env
echo "已生成 deploy/.env（权限 600）。首个 OWNER 账号与初始口令见该文件末尾两行。"
