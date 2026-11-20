#!/usr/bin/env bash
# 初始化 mold_dev：Pig 基础表 + 业务表 + 角色菜单种子 + OAuth 客户端与权限补丁
#
# 用法：
#   MYSQL_PWD='<口令>' ./init-mold-db.sh
#   MOLD_DB_HOST=127.0.0.1 MOLD_DB_PORT=3306 MOLD_DB_USER=root MYSQL_PWD=... ./init-mold-db.sh
#
# 注意：pig.sql 里写的是库 `pig`，本脚本导入时统一改写为 `mold_dev`（应用默认连这个库）。
#       脚本会重建表结构，不要对已有数据的库执行。
set -euo pipefail
: "${MYSQL_PWD:?请通过 MYSQL_PWD 提供数据库口令，避免出现在命令行}"
HOST="${MOLD_DB_HOST:-127.0.0.1}"
PORT="${MOLD_DB_PORT:-3306}"
USER="${MOLD_DB_USER:-root}"
DIR="$(cd "$(dirname "$0")" && pwd)"

mysql_run() { mysql --protocol=TCP -h "$HOST" -P "$PORT" -u "$USER" "$@"; }

echo "1/5 建库 mold_dev"
mysql_run -e "CREATE DATABASE IF NOT EXISTS \`mold_dev\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

echo "2/5 Pig 基础表（pig.sql，库名改写为 mold_dev）"
sed -e 's/`pig`/`mold_dev`/g' "$DIR/pig.sql" | mysql_run

echo "3/5 业务表（mold_schema.sql）"
mysql_run mold_dev < "$DIR/mold_schema.sql"

echo "4/5 角色与菜单种子（mold_pig_bridge.sql）"
mysql_run mold_dev < "$DIR/mold_pig_bridge.sql"

echo "5/5 OAuth 客户端与权限补丁"
for f in mold_m2_client.sql mold_m6_issue_permissions.sql mold_cash_visibility.sql; do
  echo "  - $f"
  mysql_run mold_dev < "$DIR/$f"
done

echo "完成。默认管理员 admin / 123456，首次登录后立即改密；生产密钥用 rotate-mold-client-secret.sh 轮换。"
