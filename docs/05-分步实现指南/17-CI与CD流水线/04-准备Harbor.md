# 04：准备 Harbor

> 目标：有一个可推送的私有镜像项目，能放下 `vod-api` / `vod-worker`。  
> 上一篇：[仓库与分支保护](./03-仓库与分支保护.md)　下一篇：[配置 CI 变量](./05-配置CI变量.md)

## 本步要做什么

Harbor 是公司内部的 Docker 镜像仓库。CI 构建完镜像后 `docker push` 到这里；演示机再 `docker pull`。

## 操作步骤

在 Harbor（或公司已有实例）中：

1. 新建项目，例如 `lite-vod`（私有）
2. 建机器人账号或普通用户，权限至少能 **push** 到该项目
3. 记下：
   - 仓库地址：`harbor.example.com`（占位，换成真实主机）
   - 镜像全名：
     - `harbor.example.com/lite-vod/vod-api`
     - `harbor.example.com/lite-vod/vod-worker`
4. 确认目标机（演示机）网络能访问该 Harbor，并能 `docker login`

## 本地验证推送（可选）

先在本机打出镜像（需已 `mvn package`）：

```bash
cd lite-vod
docker build -t vod-api:local -f vod-api/Dockerfile vod-api
docker login harbor.example.com
docker tag vod-api:local harbor.example.com/lite-vod/vod-api:dev
docker push harbor.example.com/lite-vod/vod-api:dev
```

Web UI 中能看到 `lite-vod/vod-api:dev` 即成功。

## 注意

- 当前 Dockerfile 是 `COPY target/*.jar`，本地验证前必须先 `mvn -B -DskipTests package`
- HTTP（非 HTTPS）Harbor 可能要在 Docker daemon 配 insecure-registries（按公司规范）
- 机器人账号密码之后只放进 GitLab Variables，不要写进文档或脚本仓库

## 完成标准

- [ ] Harbor 项目 `lite-vod`（或等价名）已创建
- [ ] 有可 push 的账号，并能 `docker login`
- [ ] （建议）手动 push 过至少一个 tag，UI 可见

## 本步不做

- 不写 GitLab CI 自动推送（见 07）
- 不改目标机 compose（见 06）
