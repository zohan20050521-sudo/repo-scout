# syntax=docker/dockerfile:1

# ---- 构建层:用项目自带 ./mvnw 打包,不额外安装 Maven ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 先只拷贝依赖描述与 wrapper,利用镜像分层缓存:
# 改动 src/ 不会让下面的 dependency:go-offline 层失效,避免重复拉依赖。
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# 再拷贝源码并打包
COPY src/ src/
# -DskipTests 是有意的单点决策(非偷懒跳过):
#   1) 测试由 CI 的 ./mvnw -B verify 负责,镜像构建只做打包;
#   2) 构建容器内没有 Redis / 外部网络依赖,在此跑集成测试既慢又无意义。
RUN ./mvnw -B package -DskipTests

# ---- 运行层:精简 JRE,非 root 运行 ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 创建非 root 系统用户运行,缩小容器被攻破后的影响面
RUN groupadd --system app && useradd --system --gid app --no-create-home app

# 仅拷贝可执行 jar(spring-boot repackage 产物;原始包为 *.jar.original,不匹配 *.jar)
COPY --from=build /app/target/*.jar /app/app.jar

USER app
EXPOSE 8080

# Java 17 自带容器感知(默认按 cgroup 限额计算堆内存),无需额外 JVM 调参
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
