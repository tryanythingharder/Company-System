# 交接文档（2026-11-25）

写给接手这个项目的人或下一个 AI。先看这份，再看 `docs/PRODUCTION_READINESS.md`（评估与整改记录，含“还没做什么”的完整清单）和 `deploy/README.md`（部署、发版、恢复、密钥轮换）。

## 一、这是什么

模具加工管理系统：项目 / 阶段 / 异常 / 采购 / 收支 / 审计。前端 Vue 3 + Element Plus + Vite，后端 Spring Boot（基于 pig-mesh），MySQL 8.4 + Redis 7。

- 仓库：`https://github.com/tryanythingharder/Company-System`
- 当前分支：`fix/business-logic-and-ux-20261124`（从 `main` 分出，已推送到 origin）
- 本机部署：`http://127.0.0.1:8080`，四个容器 `mold-{mysql,redis,backend,nginx}-1` 全部 healthy

## 二、现在是什么状态

已完成三轮：

| 轮次 | 内容 | 记录位置 |
|---|---|---|
| 1 | 生产整改：客户端密钥下架、遗留 OAuth 客户端清理、限流、乐观锁冲突、日志脱敏、Druid 连接池、优雅停机、备份与冒烟 | `docs/PRODUCTION_READINESS.md` 一～三节 |
| 2 | 业务逻辑：阶段作废、项目暂停/恢复/取消、老板代操作、超付校验、只读角色收权、幂等 | 同文件第四节 |
| 3 | 界面改版：左侧导航、角色工作台、术语口语化、统计卡可筛选、首页导航修复 | 同文件第五节 |

对外的账号只有一个：`owner`（老板），密码在 `deploy/.env` 的 `MOLD_BOOTSTRAP_OWNER_PASSWORD`。**上线前必须改掉**，改完把这两行从 `.env` 删掉。

## 三、三分钟跑起来

```bash
cd /Users/ellen/Desktop/编程项目/Company-System
docker compose -f deploy/docker-compose.prod.yml ps      # 四个 healthy 就说明栈在跑
docker compose -f deploy/docker-compose.prod.yml logs -f --tail=100 backend
```

改完前端只需重建 nginx 这一个镜像（vite 构建约 2～4 分钟）：

```bash
docker build -f deploy/frontend.Dockerfile -t mold/frontend:${MOLD_VERSION:-latest} .
docker compose -f deploy/docker-compose.prod.yml up -d --no-build nginx
```

`MOLD_VERSION` 在 `deploy/.env` 里（当前 `7c00320`），compose 用这个 tag；上面命令不传 `-t` 就会和 compose 对不上，容器会起旧镜像。

改后端的重建会顺带跑单元测试，失败即中止：

```bash
docker compose -f deploy/docker-compose.prod.yml build backend && docker compose -f deploy/docker-compose.prod.yml up -d backend
```

## 四、代码地图

```
pig/                         后端（多模块 Maven）
  db/                        业务库 DDL、增量 SQL（幂等，已有库要手工执行）
  pig-boot/src/main/java/com/pig4cloud/pig/mold/   业务模块：项目、阶段、异常、采购、收支、审计
pig-ui/src/
  api/mold/                  业务接口封装；permissions.ts 是权限判断的唯一入口
  components/mold/           业务组件（状态标签、金额、时间线、附件面板、操作菜单）
  views/mold/                业务页面：projects / project/detail / my-stages / purchases / cash / corrections / accounts / audit / stage-history
  views/home/workbench.vue   首页工作台
  utils/moldFormat.ts        时间（Asia/Shanghai）、金额（整数分）格式化的唯一入口
  utils/moldErrorHandler.ts  统一错误提示（409 / 403 / 422 的分支文案在 api/mold/errors.ts）
deploy/
  docker-compose.prod.yml    生产编排（只暴露 nginx）
  backend.Dockerfile         多阶段构建，非 root，健康检查，构建时跑单测
  frontend.Dockerfile        npm ci + vite build → nginx
  mysql/init/                首次初始化：导入上游 SQL、业务 DDL、权限种子、安全加固
  nginx/                     反代与安全头；已屏蔽上游原生端点
  e2e/                       端到端测试与浏览器脚本（见第六节）
  gen-secrets.sh             生成 .env（600 权限，不入库）
  backup.sh / smoke-test.sh  备份与冒烟
```

## 五、改代码前必须知道的约定

不遵守这些约定，很容易“改对了页面、改错了账”。

1. **时间**：业务日期一律按 `Asia/Shanghai`。后端用 `AuditLogService.beijingDayStartUtc` 之类的方法换算，前端用 `utils/moldFormat.ts` 的 `todayStamp` / `formatDate`。**不要**用 `new Date().toISOString().slice(0,10)`，那是 UTC，北京时间 0–8 点会差一天。
2. **金额**：数据库是 `decimal(14,2)`，后端 `BigDecimal` HALF_UP。前端不要用浮点 `qty * price` 或 `toFixed`，用 `mulMoneyHalfUp` / `sumMoney`（整数分 + BigInt）。
3. **流水不可改**：金额、日期、归属只能通过“更正”（生成替换流水）或“作废”（软删，净额归零）修正；已对账的流水要先取消对账。改这两个动作前先看 `CashController` 与 `ProjectService` 的加锁顺序（先锁采购单，再锁流水），否则会死锁。
4. **权限**：前端只做“按钮显示与否”，判定一律走 `useMoldPermissions()`；后端 `@PreAuthorize` 是唯一裁决。新增接口必须同时在 `pig/db/` 的权限种子里加权限点，否则默认没人有权限。
5. **写操作**：编辑、完工、暂停/取消、阶段推进都要带 `If-Match`（版本号）和 `Idempotency-Key`；409 `RETRYABLE_CONFLICT` 是让人重新加载，不是 bug。
6. **错误文案**：不要往外抛英文错误码。后端返回 `errorCode`，前端在 `api/mold/errors.ts` 里映射成“为什么不能做、下一步做什么”。
7. **术语**：面向车间和财务，写“密码”不写“口令”，写“保存时提示你重新加载”不写“返回 409 / If-Match”。
8. **时区与问候语**：问候语也按北京时间（`utils/formatTime.ts` 的 `formatAxis`），不要用浏览器本地小时。
9. **布局**：首页与其它页共用同一套左侧导航和多标签栏（`layout/component/aside.vue`、`layout/main/classic.vue` 里原来对 `/home` 的特判已去掉，别再加回来）。改了 `themeConfig` 默认值要顺手改 `App.vue` 里的 `UI_VERSION`，否则老用户浏览器里还是旧布局。

## 六、测试怎么跑

后端接口测试（Python，直接打真实部署栈，会写业务数据）：

```bash
cd deploy/e2e
python3 setup_accounts.py            # 先启用/创建 6 个 e2e_ 测试账号（.accounts.json）
python3 business_flow.py             # 主流程，214 条
python3 purchase_flow.py             # 采购，87 条
python3 purchase_concurrency.py      # 并发超付，22 条
python3 perm_matrix.py               # 67 接口 × 6 角色
python3 decisions_flow.py            # 业务决策回归，73 条
docker compose -f ../docker-compose.prod.yml exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot mold_dev' \
  < cleanup_test_data.sql            # 跑完清理（先备份！见下）
```

浏览器脚本（Chrome，需本机装有 Chrome）：

```bash
node deploy/e2e/ui_layout_check.cjs   # 5 角色 × 3 屏宽的溢出/结构检测
node deploy/e2e/ui_shots.cjs          # 按角色批量截图到 ui_shots_out/（已 gitignore）
```

**注意**：

- 这些脚本会往当前库写数据。**不要**对生产库运行。跑之前先 `./deploy/backup.sh`，跑完用 `deploy/e2e/cleanup_test_data.sql`（以 root 在 mysql 容器里执行）清理，并停用测试账号。
- 当前库是干净的：7 个 `e2e_` 账号已停用，只剩 `owner`；备份在 `deploy/backups/20261124T071437Z` 和 `20261124T081237Z`。
- `ui_layout_check.cjs` / `ui_shots.cjs` 读 `.accounts.json`（gitignore），里面的账号必须是启用状态。
- Playwright 不在项目依赖里，脚本用 `~/.npm/_npx/.../playwright` 且 `channel: 'chrome'`（本机没装 chromium 内核）。
- `.accounts.json`、`ui_shots_out/`、各类 `*_report.json` 都不入库，别误提交。

## 七、还没做的事（按优先级）

### 上线前

1. 改掉 `owner` 的初始密码，并从 `.env` 删除 `MOLD_BOOTSTRAP_OWNER_LOGIN/PASSWORD`（见 `deploy/README.md`“上线前必须确认”）。
2. 已有库要执行增量 SQL：`pig/db/mold_viewer_readonly.sql`、`pig/db/mold_owner_transfer_only.sql`（幂等），然后清 Redis 的 `gl:menu_details::*`、`gl:user_details::*`。
3. 前面必须有 TLS 终止层；本栈只绑 `127.0.0.1:8080`。

### 功能与安全（P1，详见评估报告第二节）

1. **对象级越权（IDOR）**：项目/阶段/异常/附件按 ID 读没有校验成员关系，只要有权限就能按 ID 读到别人的项目。修法：统一 `requireProjectRead(actor, projectId)`。要先和业务确认“是否所有员工都能看所有项目”。
2. **两张表无限增长**：`idempotency_keys`、`event_outbox` 没有清理任务。
3. **登录审计缺失**：登录成功/失败/锁定都不写审计。
4. **SSE 按连接轮询数据库**：每个连接每秒查 4 次，在线人数上去会拖垮 Druid 池。
5. **下拉只取 100 条**：项目、往来单位、用户超过 100 条会被悄悄截断，改远程搜索（`views/home/workbench.vue` 的 100 条也需要一并改）。

### 界面（P2）

1. 对比度与键盘可达性没逐项测（可用 axe-core）；目前只给统计卡补了 `role/tabindex/aria-pressed`。
2. 手机端只验证了“不溢出”，字段多的页面（审计日志、采购详情）要真机点一遍。
3. 产物里仍打包 117 个上游页面（代码生成、客户端管理等），后台菜单能路由过去，建议裁掉。
4. `echarts < 6.1.0` 有 XSS（`npm audit` 中危），升级是破坏性变更。

## 八、坑（踩过的）

| 坑 | 现象 | 处理 |
|---|---|---|
| Docker 构建缓存 | `docker build` 全是 CACHED，以为没重建 | 缓存键是文件内容哈希；确认 `COPY pig-ui/ ./` 那一层不是 CACHED，或加 `--no-cache-filter=build` |
| compose 的镜像 tag | `up -d` 后页面没变 | 必须用 `deploy/.env` 里的 `MOLD_VERSION` 作 tag，或改 `.env` 再 `up -d` |
| nginx 重复指令 | 容器起不来，日志报 duplicate | `proxy_read_timeout` 只在 SSE 段写一次 |
| npm 源 | 容器内 `npm ci` 连接被重置 | 默认走 `registry.npmmirror.com`，可用 `--build-arg NPM_REGISTRY=` 覆盖 |
| SSE 与 `networkidle` | Playwright 等 `networkidle` 会超时 | 用 `domcontentloaded` + 等具体元素 |
| bash 变量后接中文 | 脚本 `unbound variable` 中途退出 | 一律写 `${var}` |
| 写文件时的脱敏 | 含 `PASSWORD=` 的行被替换成占位符 | 先写占位符再用脚本还原；提交前 `git diff` 搜一遍 `REDACTED`、`%%` |
| 时区 | 北京时间 0–8 点日期差一天 | 见第五节第 1 条 |

## 九、下一步建议（如果只有半天）

1. 用 `setup_accounts.py` 起测试账号，跑一遍 `business_flow.py` + `perm_matrix.py`，确认当前分支没回归；跑完清理。
2. 做第 7 节 P1 的 IDOR（先问业务口径），这是唯一还能被外部利用的洞。
3. 把 `deploy/e2e/` 接进 CI（GitHub Actions），每次 PR 跑 `mvn verify`、`npm ci && vite build`、冒烟。
