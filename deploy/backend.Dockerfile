# syntax=docker/dockerfile:1.7
# 后端生产镜像（多阶段）：在容器内编译 + 跑单测，不依赖宿主机 JDK/Maven。
# 构建：docker compose -f deploy/docker-compose.prod.yml build backend

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY maven-settings.xml /usr/share/maven/ref/settings-docker.xml
COPY pig/ ./
# -Pboot：单体 pig-boot；-Pprod：剔除仅用于验收的 /mold/_verify 控制器。
# 第 1 步：上游依赖模块只编译安装（其自带测试依赖 Nacos 等外部环境）；
# 第 2 步：pig-boot 打包并运行业务单测，失败即构建失败。紧急情况可 --build-arg SKIP_TESTS=true
ARG SKIP_TESTS=false
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -s /usr/share/maven/ref/settings-docker.xml -Pboot,prod -pl pig-boot -am install -DskipTests \
    && mvn -B -s /usr/share/maven/ref/settings-docker.xml -Pboot,prod -pl pig-boot package \
        -DskipTests=${SKIP_TESTS} -Dmaven.test.failure.ignore=false \
    && cp pig-boot/target/pig-boot.jar /app.jar

FROM eclipse-temurin:17-jre
ARG BUILD_VERSION=dev
LABEL org.opencontainers.image.title="mold-backend" org.opencontainers.image.version="${BUILD_VERSION}"
RUN groupadd --system --gid 10001 mold \
    && useradd --system --uid 10001 --gid mold --home-dir /app --shell /usr/sbin/nologin mold \
    && mkdir -p /app/data/files /app/logs \
    && chown -R mold:mold /app
WORKDIR /app
COPY --from=build --chown=mold:mold /app.jar /app/app.jar
USER mold
ENV TZ=UTC \
    MOLD_BUILD_VERSION=${BUILD_VERSION} \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom"
EXPOSE 9999
# exec 让 java 成为 PID 1 并接收 SIGTERM，配合 server.shutdown=graceful 优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
