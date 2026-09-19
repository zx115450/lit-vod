# 02：GitLab CI 配置与镜像下载优化

> 目标：让 `.gitlab-ci.yml` 在首次运行时尽量减少国外下载，主要时间花在 Maven 依赖而不是镜像/包管理器上。

## 最终流水线链路

```text
git push main
  → test（Maven 单测）
  → build_push（mvn package + docker build + push to Harbor）
  → deploy_staging（手动：SSH 到演示机，compose pull && up）
```

## 镜像选择

| 阶段 | 镜像 | 原因 |
| --- | --- | --- |
| test | `maven:3.9-eclipse-temurin-17` | 自带 JDK/Maven |
| build_push | `maven:3.9-eclipse-temurin-17` | 不再在 `docker:24` 里 `apk add` JDK/Maven |
| build_push service | `docker:24-dind` | 提供 Docker 引擎，构建容器只装 CLI |
| deploy_staging | `alpine:3.19` | 只需要 openssh-client + curl |

## 镜像源替换

- Docker 基础镜像：宿主机已预拉 `maven:...`、`docker:24-dind`、`eclipse-temurin:...`，Runner 策略 `if-not-present`。
- Maven 依赖：`.gitlab-ci.yml` 生成 `~/.m2/settings.xml`，central 走阿里云。
- Alpine `apk` 改为阿里云：之前 `build_push` 用 `docker:24` 镜像时，需要临时安装 JDK/Maven，已替换为 Maven 镜像，避免此问题。
- Ubuntu `apt-get`（vod-worker Dockerfile 装 ffmpeg）：换阿里云 Ubuntu 源。
- Docker CLI 下载：优先 `mirrors.aliyun.com/docker-ce/...`，失败回退官方。
- dind 的 Docker 引擎拉基础镜像：配 `--registry-mirror=https://docker.m.daocloud.io`。

## .gitlab-ci.yml 关键片段

```yaml
build_push:
  stage: build
  image: maven:3.9-eclipse-temurin-17
  services:
    - name: docker:24-dind
      command:
        - "--insecure-registry=192.168.150.104:8089"
        - "--registry-mirror=https://docker.m.daocloud.io"
  variables:
    DOCKER_HOST: "tcp://docker:2375"
    DOCKER_TLS_CERTDIR: ""
```

## 学到的坑

1. **不要在 `docker:24` 里 `apk add openjdk17-jdk maven`**：Alpine 官方源极慢，会卡十几分钟。
2. **`docker:24` 和 `docker:24-dind` 不能混用**：`docker:24` 在某些镜像源实际上是 dind 镜像，客户端应使用 `docker:24-cli`。
3. **Harbor 变量不要带空白**：`HARBOR_HOST` 里夹了 `\t\n`，`docker login` 会报 `invalid control character in URL`。
4. **缓存只在任务成功后写入**：第一次如果失败，`.m2` 不会留到下一次，仍会重新下载 Maven 依赖。
