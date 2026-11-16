# M6 镜像修复：把完整 Spring Boot fat jar 打进镜像，避免运行时再依赖宿主 bind mount。
# 背景：旧容器用 bind mount 指向 target/pig-boot.jar；构建期间该文件被替换，
# 运行中的 JVM 之后才加载的类（如 logback ThrowableProxy）读不到，错误口令路径直接
# NoClassDefFoundError 并挂死。镜像内 COPY 固定副本可消除这一类问题。
#
# 构建（在 pig-spike-20261112 目录）：
#   mkdir -p /tmp/m6-image && cp pig/pig-boot/target/pig-boot.jar /tmp/m6-image/pig-boot.jar
#   docker build -f pig/docker/m6/Dockerfile -t pig-mold-m2:m6fix /tmp/m6-image
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY pig-boot.jar /app/app.jar

EXPOSE 9999
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
