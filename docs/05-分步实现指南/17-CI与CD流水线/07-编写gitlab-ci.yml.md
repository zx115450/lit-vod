# 07：编写 `.gitlab-ci.yml`

> 目标：仓库根目录落地流水线：`test` → `build_push` →（main 手动）`deploy_staging`。  
> 上一篇：[Compose 改为可拉镜像](./06-Compose改为可拉镜像.md)　下一篇：[目标机初始化与部署](./08-目标机初始化与部署.md)

## 本步要做什么

在**仓库根**新增 `.gitlab-ci.yml`。若业务代码在子目录，用变量 `LITE_VOD_DIR: "lite-vod"` 统一 `cd`。

## 示意配置

```yaml
# 仓库根：.gitlab-ci.yml
# 链路：test → build&push →（main）deploy

variables:
  LITE_VOD_DIR: "lite-vod"
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  IMAGE_TAG: "$CI_COMMIT_SHORT_SHA"

stages:
  - test
  - build
  - deploy

# ---------- CI：单测 ----------
test:
  stage: test
  image: maven:3.9-eclipse-temurin-17
  cache:
    key: maven-$CI_COMMIT_REF_SLUG
    paths:
      - .m2/repository
  script:
    - cd "$LITE_VOD_DIR"
    - mvn -B test
  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_COMMIT_BRANCH

# ---------- 制品：打包 + 构建 + 推 Harbor ----------
build_push:
  stage: build
  image: docker:24
  services:
    - docker:24-dind
  variables:
    DOCKER_TLS_CERTDIR: "/certs"
  needs: ["test"]
  before_script:
    - apk add --no-cache openjdk17-jdk maven
    # 若 Runner 已预装 JDK/Maven，可删掉上一行，改用 maven 镜像 + kaniko / buildah
  script:
    - cd "$LITE_VOD_DIR"
    - mvn -B -DskipTests package
    - echo "$HARBOR_PASSWORD" | docker login -u "$HARBOR_USER" --password-stdin "$HARBOR_HOST"
    - |
      API_IMAGE="$HARBOR_HOST/$HARBOR_PROJECT/vod-api:$IMAGE_TAG"
      WORKER_IMAGE="$HARBOR_HOST/$HARBOR_PROJECT/vod-worker:$IMAGE_TAG"
      docker build -t "$API_IMAGE" -f vod-api/Dockerfile vod-api
      docker build -t "$WORKER_IMAGE" -f vod-worker/Dockerfile vod-worker
      docker push "$API_IMAGE"
      docker push "$WORKER_IMAGE"
      # 可选：再打 latest，仅 main
      if [ "$CI_COMMIT_BRANCH" = "main" ]; then
        docker tag "$API_IMAGE" "$HARBOR_HOST/$HARBOR_PROJECT/vod-api:latest"
        docker tag "$WORKER_IMAGE" "$HARBOR_HOST/$HARBOR_PROJECT/vod-worker:latest"
        docker push "$HARBOR_HOST/$HARBOR_PROJECT/vod-api:latest"
        docker push "$HARBOR_HOST/$HARBOR_PROJECT/vod-worker:latest"
      fi
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
      when: manual   # MR 上构建镜像改为手动，省配额；可按团队改成 always

# ---------- CD：SSH 演示机 Compose 滚动 ----------
deploy_staging:
  stage: deploy
  image: alpine:3.19
  needs: ["build_push"]
  before_script:
    - apk add --no-cache openssh-client curl
    - mkdir -p ~/.ssh && chmod 700 ~/.ssh
    - echo "$DEPLOY_SSH_KEY" | tr -d '\r' > ~/.ssh/id_rsa
    - chmod 600 ~/.ssh/id_rsa
    - ssh-keyscan -H "$DEPLOY_HOST" >> ~/.ssh/known_hosts
  script:
    - |
      ssh "$DEPLOY_USER@$DEPLOY_HOST" "set -e
        cd '$DEPLOY_PATH'
        export IMAGE_TAG='$IMAGE_TAG'
        grep -q '^IMAGE_TAG=' .env 2>/dev/null && sed -i \"s/^IMAGE_TAG=.*/IMAGE_TAG=$IMAGE_TAG/\" .env || echo \"IMAGE_TAG=$IMAGE_TAG\" >> .env
        docker login -u '$HARBOR_USER' --password-stdin '$HARBOR_HOST' <<< '$HARBOR_PASSWORD'
        docker compose pull vod-api vod-worker
        docker compose up -d vod-api vod-worker
        sleep 5
        curl -fsS http://127.0.0.1:8080/vod/health
      "
  environment:
    name: staging
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
      when: manual   # 演示环境建议手动点 Deploy
```

## 与现有 Dockerfile 的衔接

- 当前是 `COPY target/*.jar`，**必须先** `mvn package` 再 `docker build`
- 更贴企业习惯时可改成多阶段 Dockerfile（`FROM maven … AS build` → `FROM jre`），则 CI 可只 `docker build`
- 依赖外网 MinIO / MySQL 的集成测：CI 无环境时继续 `@Disabled` 或 `profile=local`
- Worker 镜像已含 ffmpeg；单测若不跑 ffmpeg，默认 Runner 即可

## 本地先模拟核心命令

```bash
cd lite-vod
mvn -B test
mvn -B -DskipTests package
docker build -t vod-api:local -f vod-api/Dockerfile vod-api
docker build -t vod-worker:local -f vod-worker/Dockerfile vod-worker
```

## 完成标准

- [ ] 仓库根存在 `.gitlab-ci.yml`
- [ ] MR 上 `test` Job 会自动跑
- [ ] `main` 上 `build_push` 能推到 Harbor（tag = 短 sha）
- [ ] `deploy_staging` 为手动 Job（或团队约定的触发方式）

## 本步不做

- 不上 Kaniko / 多环境矩阵（可后补）
- 不做全量 E2E（留本地脚本或夜间 Job）
