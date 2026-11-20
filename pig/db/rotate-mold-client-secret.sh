#!/usr/bin/env bash
# 轮换 mold:mold OAuth 客户端密钥（M6 前置）
#
# 只更新 mold_dev 的 sys_oauth_client_details 一行；不写文件、不回显密钥。
# 用法：
#   export MOLD_AUTH_CLIENT_SECRET="$(openssl rand -base64 48 | tr '+/' '-_' | tr -d '=\n')"
#   export MYSQL_PWD='<数据库口令>'
#   ./rotate-mold-client-secret.sh
# 之后：把同一个值注入应用环境变量 MOLD_AUTH_CLIENT_SECRET，重启应用。
#
# 注意：Pig 读取 client_secret 时统一加 {noop} 前缀，数据库里是明文；
# 本脚本只负责同步数据库与部署密钥，不改变该存储方式（见报告第 2 节残留风险）。
set -euo pipefail

: "${MOLD_AUTH_CLIENT_SECRET:?请先导出 MOLD_AUTH_CLIENT_SECRET（openssl rand -base64 48 | tr -d '\\n'）}"
: "${MYSQL_PWD:?请通过 MYSQL_PWD 提供数据库口令，避免出现在命令行}"
: "${MOLD_DB_HOST:=127.0.0.1}"
: "${MOLD_DB_PORT:=3306}"
: "${MOLD_DB_USER:=root}"
: "${MOLD_DB_NAME:=mold_dev}"

# 只允许 base64url 字符（A-Za-z0-9_-）。
# 必须排除 '+' '/' '='：实测密钥含 '+' 时 Spring Authorization Server 的客户端认证
# 恒定失败（invalid_client / "Client authentication failed: client_secret"），
# 因为 '+' 在客户端认证链路里被当作空格解码。base64url 不含这三个字符，最稳。
case "$MOLD_AUTH_CLIENT_SECRET" in
  *[!A-Za-z0-9_-]*) echo "MOLD_AUTH_CLIENT_SECRET 含非 base64url 字符（禁止 + / =），已拒绝" >&2; exit 2 ;;
esac

mysql --protocol=TCP -h "$MOLD_DB_HOST" -P "$MOLD_DB_PORT" -u "$MOLD_DB_USER" "$MOLD_DB_NAME" <<SQL
UPDATE sys_oauth_client_details
   SET client_secret = '${MOLD_AUTH_CLIENT_SECRET}'
 WHERE client_id = 'mold' AND del_flag = '0';
SELECT ROW_COUNT() AS updated_rows;
SQL

echo "数据库已更新；请把同一密钥注入 MOLD_AUTH_CLIENT_SECRET 并重启应用，然后验证登录。"
