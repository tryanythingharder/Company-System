# syntax=docker/dockerfile:1.7
# 前端生产镜像（多阶段）：npm ci（锁文件可复现）+ vite build，产物交给 nginx。
FROM node:22-alpine AS build
WORKDIR /src
COPY pig-ui/package.json pig-ui/package-lock.json ./
# 默认走国内镜像（与 maven-settings.xml 的阿里云镜像一致）；海外构建可 --build-arg NPM_REGISTRY=https://registry.npmjs.org/
# npm 默认 replace-registry-host=npmjs，会把锁文件里的 registry.npmjs.org 地址替换为这里的镜像，锁定版本与校验和不变。
ARG NPM_REGISTRY=https://registry.npmmirror.com/
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund --registry="${NPM_REGISTRY}" \
        --fetch-retries=5 --fetch-retry-mintimeout=20000 --fetch-retry-maxtimeout=120000
COPY pig-ui/ ./
# 直接调用 vite，堆上限按构建机内存设置（上游脚本写死 8GB）
ARG NODE_HEAP_MB=2560
RUN NODE_OPTIONS=--max-old-space-size=${NODE_HEAP_MB} npx vite build --mode production

FROM nginx:1.27-alpine
RUN rm -f /etc/nginx/conf.d/default.conf
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/mold.conf deploy/nginx/proxy_params.inc deploy/nginx/security_headers.inc /etc/nginx/conf.d/
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
