# Company-System

小公司（模具加工/零件加工）管理系统：项目与阶段进度、收支流水、往来单位、账号与审计。

## 技术栈

| 部分 | 技术 |
|---|---|
| 后端 `pig/` | Java 17 / Spring Boot / MyBatis-Plus / MySQL 8 / Redis，基于 [pig-mesh/pig](https://github.com/pig-mesh/pig) 二开 |
| 前端 `pig-ui/` | Vue 3.5 / Element Plus / Vite，基于 [pig-mesh/pig-ui](https://github.com/pig-mesh/pig-ui) 二开 |
| 数据库 | MySQL 8，初始化脚本在 `pig/db/` |

## 目录

```
pig/                  后端（单体 pig-boot，网关前缀 /admin）
  pig-boot/           业务服务：项目、阶段、流水、采购、更正、审计
  db/                 建库脚本与迁移
  docker/             容器与反代配置
pig-ui/               前端
  src/views/mold/     业务页面：项目、阶段、流水、采购、更正
docker-compose-spike.yml   本地依赖（MySQL / Redis）
maven-settings.xml         Maven 镜像配置
```

## 本地启动

```bash
# 依赖
docker compose -f docker-compose-spike.yml up -d

# 后端
cd pig && mvn -pl pig-boot -am package -DskipTests=false
java -jar pig-boot/target/pig-boot.jar --spring.profiles.active=m2

# 前端
cd pig-ui && npm install && npm run dev
```

默认后端监听 `9999`，context-path `/admin`；前端开发代理指向该地址。

## 权限模型

固定六角色：`OWNER`（老板）、`FINANCE`（财务）、`PM`（项目负责人）、`PROGRAMMER`（编程）、`MACHINIST`（操机）、`VIEWER`（只读）。

收支流水的可见性：持有 `mold:cash:viewAll` 的角色（老板、财务）看全公司；其余角色只看自己录入的（`created_by`）。报表与事件流的财务数据同口径判定。

## 说明

- 本仓库只含源码与配置，不含内部文档、验收脚本、构建产物与运行数据。
- 上游 pig / pig-ui 采用 Apache License 2.0，本仓库保留其许可证文件。
- 配置中的数据库口令为本地开发默认值，部署时请通过环境变量覆盖（`MOLD_AUTH_CLIENT_SECRET` 等）。
