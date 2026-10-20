# =====================================================================
# nex-home 多架构镜像构建文件（linux/amd64 & linux/arm64）
#
# 未使用 # syntax 指令：避免 buildkit 额外拉取 docker/dockerfile 前端镜像，
# 本文件仅用标准语法，buildkit 内置前端即可解析
#
# 多阶段构建：
#   - 编译阶段固定 --platform=$BUILDPLATFORM（构建机原生架构）运行 Maven，
#     fat-jar 为纯 Java 产物（sqlite-jdbc 运行时自动加载对应架构 native 库），
#     无需按目标架构重复编译，避免 QEMU 模拟，多架构构建速度更快
#   - 运行阶段基于 eclipse-temurin:25-jdk-noble（官方多架构镜像），与 pom.xml
#     中 fabric8 插件的镜像内容保持一致（app.jar + config.yaml）
#
# 本地手动构建多架构镜像：
#   docker buildx build --platform linux/amd64,linux/arm64 \
#     -t nex-home:latest --push .
# 国内环境构建可注入阿里云 Maven 镜像加速依赖下载：
#   --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public
# =====================================================================

# ---------- 阶段一：Maven 编译打包 ----------
ARG MAVEN_IMAGE=maven:3-eclipse-temurin-25
# 运行时基础镜像：必须声明在第一个 FROM 之前（全局 ARG），否则后续 FROM 无法引用
ARG BASE_IMAGE=eclipse-temurin:25-jdk-noble
FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build
WORKDIR /build

# 可选：注入 Maven 镜像仓库（阿里云自动构建等国内环境可加速依赖下载）
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
            "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi

# 先拷贝 pom.xml 预取依赖，源码变更时可复用 Docker 层缓存
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- 阶段二：运行时镜像 ----------
FROM ${BASE_IMAGE}
WORKDIR /app

COPY --from=build /build/target/nexhome.jar app.jar

# JVM 内存自适应：堆上限按容器内存限制（cgroup；裸机运行时为物理总内存）的 50% 自动计算
# （-XX:MaxRAMPercentage），无需固定 -Xmx，低配/高配环境均可自适应；
# 剩余 ~50% 留给 Metaspace(≤128m)/CodeCache(≤64m)/线程栈等非堆内存；
# 低内存调优：Serial GC + 小线程栈（ip2region 已用 VIndexCache 按需读文件，无需大堆）
# NativeMemoryTracking=summary：开启轻量原生内存跟踪，供「内存诊断」页拆分堆外（Netty 池化等）占用；仅记类别汇总，开销约 5% 内、无额外线程
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+UseCompactObjectHeaders", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=50.0", "-XX:NativeMemoryTracking=summary", "-Xss512k", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-jar", "app.jar"]

EXPOSE 8090
