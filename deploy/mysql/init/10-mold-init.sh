#!/bin/bash
# MySQL 容器首次初始化（空数据卷时由官方 entrypoint 执行一次；已有数据不会再执行）。
#
#  1) 导入 Pig 基础表（库名 pig → mold_dev）、业务表、角色菜单种子、OAuth 客户端与权限补丁；
#  2) 生产加固：
#     - 删除 Pig 遗留 OAuth 客户端（app/pig/social/mini，密钥即客户端名，可绕过登录限流爆破）；
#     - mold 客户端密钥改为部署环境注入的随机值；
#     - 锁定 Pig 默认管理员 admin（默认口令公开，且未绑定业务身份）；
#     - 创建业务库最小权限账号（只有 DML，不能 DDL，强制 TLS）。
# 首个 OWNER 账号由后端启动时按 MOLD_BOOTSTRAP_OWNER_* 创建（BCrypt 哈希在应用内生成）。
set -euo pipefail

DB_USER="${MOLD_DB_USER:?缺少 MOLD_DB_USER}"
DB_PW="${MOLD_DB_PASSWORD:?缺少 MOLD_DB_PASSWORD}"
CLIENT_SC="${MOLD_AUTH_CLIENT_SECRET:?缺少 MOLD_AUTH_CLIENT_SECRET}"

# 这些值会拼进 SQL：只允许 base64url 字符，杜绝引号注入
for v in "$DB_USER" "$DB_PW" "$CLIENT_SC"; do
  case "$v" in
    *[!A-Za-z0-9_-]*) echo "[mold-init] 密钥/账号只能包含 A-Za-z0-9_-，已拒绝" >&2; exit 2 ;;
  esac
done

export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
run() { mysql --protocol=socket --default-character-set=utf8mb4 -uroot "$@"; }
DB=/mold-db

echo "[mold-init] 1/5 建库 mold_dev"
run -e "CREATE DATABASE IF NOT EXISTS \`mold_dev\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"

echo "[mold-init] 2/5 Pig 基础表（pig.sql，库名改写为 mold_dev）"
sed -e 's/`pig`/`mold_dev`/g' "$DB/pig.sql" | run

echo "[mold-init] 3/5 业务表与角色菜单种子"
run mold_dev < "$DB/mold_schema.sql"
run mold_dev < "$DB/mold_pig_bridge.sql"

echo "[mold-init] 4/5 OAuth 客户端与权限补丁"
for f in mold_m2_client.sql mold_m6_issue_permissions.sql mold_cash_visibility.sql mold_site_brand.sql \
         mold_viewer_readonly.sql mold_owner_transfer_only.sql; do
  echo "  - $f"
  run mold_dev < "$DB/$f"
done

echo "[mold-init] 5/5 生产加固"
run mold_dev <<SQL
DELETE FROM sys_oauth_client_details WHERE client_id <> 'mold';
UPDATE sys_oauth_client_details
   SET client_secret = '${CLIENT_SC}',
       authorized_grant_types = 'password,refresh_token',
       del_flag = '0'
 WHERE client_id = 'mold';
UPDATE sys_user SET lock_flag = '9' WHERE username = 'admin';

CREATE USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PW}' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON \`mold_dev\`.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL

run mold_dev -N -e "SELECT CONCAT('[mold-init] OAuth 客户端: ', GROUP_CONCAT(client_id)) FROM sys_oauth_client_details;"
echo "[mold-init] 完成"
