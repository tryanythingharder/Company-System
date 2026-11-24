# 生产部署手册（Docker Compose 单机）

## 架构

```
浏览器 ──TLS──> [你的网关/负载均衡，终止 HTTPS] ──> nginx:8080（静态页 + /api 反代）
                                                    └─> backend:9999（pig-boot，m2,prod）
                                                          ├─> mysql:3306（8.4 LTS，强制 TLS，最小权限账号）
                                                          └─> redis:6379（requirepass，AOF）
```

只有 nginx 映射到宿主机，默认 `127.0.0.1:8080`。MySQL、Redis、后端只在内部网络 `mold_prod_net` 里可达。

## 首次部署

```bash
./deploy/gen-secrets.sh                                      # 生成 deploy/.env（随机密钥，权限 600，不入库）
docker compose -f deploy/docker-compose.prod.yml build
docker compose -f deploy/docker-compose.prod.yml up -d
docker compose -f deploy/docker-compose.prod.yml ps           # 四个服务都应为 healthy
```

- MySQL 数据卷为空时会自动执行 `deploy/mysql/init/10-mold-init.sh`，内容包括：导入建库脚本、删除遗留的 OAuth 客户端、写入随机客户端密钥、锁定 `admin`、创建最小权限账号 `mold_app`。
- 后端首次启动时，如果库里没有在职的 OWNER，会按 `.env` 里的 `MOLD_BOOTSTRAP_OWNER_LOGIN` 和对应口令创建一个 OWNER 账号，首次登录强制改密。
- 改密成功后，从 `.env` 里删掉这两行引导配置，再执行 `up -d`。

## 上线前必须确认

1. **HTTPS**：前面必须有 TLS 终止层（云负载均衡、Caddy 或 Traefik 均可），并在那一层开启 HSTS。如果直接把 8080 暴露到公网，`.env` 里的 `MOLD_HTTP_BIND` 要改成 `0.0.0.0`，同时一定要做 TLS。
2. **网关的客户端 IP**：网关应把真实客户端 IP 追加到 `X-Forwarded-For`。如果网关在宿主机之外，把网关 IP 追加到 compose 中 backend 的 `MOLD_TRUSTED_PROXY_IPS`，例如 `172.30.57.10,10.0.0.5`。
3. **备份**：配置定时备份，并且至少做一次恢复演练（见下文）。
4. **密钥管理**：`deploy/.env` 只放在服务器上，不能进仓库、聊天或邮件。

## 发版

```bash
git pull
docker compose -f deploy/docker-compose.prod.yml build        # 后端构建会运行单元测试，失败即中止
docker compose -f deploy/docker-compose.prod.yml up -d        # 逐个替换容器；后端优雅停机 25 秒
```

**数据库变更**：初始化脚本只在空库时执行一次。之后的表结构变更要写成增量 SQL，用 root 账号手工执行，执行前先备份。应用账号没有 DDL 权限，这是有意的设计。

## 备份与恢复

```bash
./deploy/backup.sh                       # 输出到 deploy/backups/<时间戳>/：mold_dev.sql.gz + files.tar.gz + SHA256SUMS
# crontab 示例：15 3 * * * /srv/Company-System/deploy/backup.sh >> /var/log/mold-backup.log 2>&1
```

备份要定期复制到另一台机器或对象存储。只存在本机的备份，磁盘坏了会一起丢。

**恢复演练**（建议在另一台机器或另一个 compose 项目里进行）：

```bash
set -a; . deploy/.env; set +a
gunzip -c deploy/backups/<ts>/mold_dev.sql.gz | \
  docker compose -f deploy/docker-compose.prod.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot'
docker compose -f deploy/docker-compose.prod.yml exec -T backend tar -C /app/data -xzf - < deploy/backups/<ts>/files.tar.gz
```

## 密钥轮换

| 密钥 | 轮换方式 |
|---|---|
| OAuth 客户端密钥 | 先用 root 执行 `UPDATE sys_oauth_client_details SET client_secret='<新值>' WHERE client_id='mold'`，再改 `.env`，然后执行 `up -d backend`。最后清掉 Redis 中的 `pig_oauth:client:details::mold`。 |
| 数据库口令 | 先用 root 执行 `ALTER USER 'mold_app'@'%' IDENTIFIED BY '<新值>'`，再改 `.env`，然后执行 `up -d backend`。 |
| Redis 口令 | 改 `.env` 后执行 `up -d redis backend`。已有会话会失效，需要重新登录。 |

新值只能用 `A-Za-z0-9_-` 字符。含 `+` 会导致 OAuth 客户端认证一直返回 `invalid_client`。

## 日常排障

```bash
docker compose -f deploy/docker-compose.prod.yml logs -f --tail=200 backend
docker compose -f deploy/docker-compose.prod.yml exec backend ls /app/logs     # 滚动文件日志（总量上限 3GB）
```

**用 requestId 串查**：每个响应都带 `X-Request-Id` 头。拿这个值可以把 nginx 访问日志、后端日志和审计表 `audit_logs.request_id` 中的记录对上。

**探针**：
- 存活：`/admin/healthz`，只看进程。
- 就绪：`/admin/actuator/health`，同时检查 DB 和 Redis。

两个探针都只在容器内使用，nginx 不对外暴露 actuator。
