# 02：部署 GitLab 与 Runner

> 目标：有一台可访问的 GitLab，并注册至少一台能跑 Job 的 Runner。  
> 上一篇：[概念与总览](./01-概念与总览.md)　下一篇：[仓库与分支保护](./03-仓库与分支保护.md)

## 本步要做什么

前面文档默认「公司已有 GitLab」。若你本地 / 实验环境还没有，本步用 **Docker Compose** 拉起一套最小可用的 GitLab CE + Runner。  
若已有公司实例，可跳过安装，只核对「能登录、能建项目、有可用 Runner」。

## 三种落地方式（选一种）

| 方式 | 适用 | 说明 |
| --- | --- | --- |
| **A. 公司已有 GitLab** | 实习 / 正式环境 | 找管理员开账号与项目；本步只验证 Runner |
| **B. Docker 自建 GitLab CE** | 学习 / Demo | 下文主路径；资源占用大，建议单独一台机 |
| **C. gitlab.com 免费账号** | 快速体验 | 无需自建；镜像仓库可改用 GHCR / Docker Hub，概念相同 |

下文以 **B** 为主；A / C 在文末对照。

## 虚拟机配置（自建 GitLab 用）

GitLab Omnibus（CE 容器）自带 PostgreSQL、Redis、Puma、Sidekiq 等，**比普通业务机更吃内存**。建议单独一台虚拟机，不要和 `lite-vod` 演示机、Harbor 挤在同一台 2G 机器上。

### 推荐规格对照

| 档位 | vCPU | 内存 | 系统盘 | 数据盘 | 适用 |
| --- | --- | --- | --- | --- | --- |
| **最低可跑（仅学习）** | 2 | **4 GB** | 40 GB | 可合并系统盘 | 1～2 人练手；页面可能慢、易 OOM |
| **本仓库 Demo 推荐** | **4** | **8 GB** | 40 GB | **50～100 GB** | 本机 CI + 少量 MR；较稳 |
| **小团队长期用** | 4～8 | **16 GB** | 50 GB | **100 GB+** | 多人、制品/Job 日志较多 |

官方参考量级（单节点 CE）：生产常见从 **8 GB 内存起**；用户/仓库变多再加 CPU 与盘。学习环境用上表「Demo 推荐」即可。

### 系统与虚拟化

| 项 | 建议 |
| --- | --- |
| 操作系统 | Ubuntu 22.04 / 24.04 LTS，或 Rocky / AlmaLinux 9 |
| 虚拟化 | VMware / Hyper-V / VirtualBox / 云主机均可 |
| 交换分区 | 4G 内存机建议开 **2～4 GB swap**，避免偶发 OOM 直接杀进程 |
| 时区 | 与业务机一致（如 `Asia/Shanghai`） |
| Docker | 先装 Docker Engine + Compose 插件，再拉 GitLab 容器 |

### 网络与端口（安全组 / 防火墙）

| 端口 | 用途 | 备注 |
| --- | --- | --- |
| **80** | HTTP Web / clone | 学习内网可只开这个 |
| **443** | HTTPS | 上公网或有域名时再开 |
| **2222**（宿主机）→ 容器 22 | Git SSH | 与下文 compose 映射一致；勿与宿主机 sshd 的 22 冲突 |
| 出站 | 拉镜像、访问 Harbor | Runner 所在机需能访问 Harbor 与演示机 |

- `external_url` 必须填 **其他机器也能访问的地址**（局域网 IP 或域名），不要只写只有 VM 自己认识的 `localhost`
- Runner、开发机、演示机与 GitLab 建议同一 VPC / 局域网

### Runner 要不要同机

| 方案 | 配置加法 | 说明 |
| --- | --- | --- |
| **同机**（学习省事） | 在 GitLab VM 上再 + **2 vCPU / +2～4 GB** | 跑 `mvn test`、DinD 时内存会顶满，8G 机同机勉强 |
| **分机**（更接近企业） | 另开一台 Runner：2～4 vCPU / 4～8 GB | Job 与 GitLab 互不影响；本仓库 `build_push` 更推荐 |

本流水线 `build_push` 使用 DinD，Runner 机磁盘也建议 ≥ 40 GB（Maven 缓存 + 中间镜像层）。

### 创建虚拟机时的勾选清单（示例）

```text
名称：gitlab-ce
OS：Ubuntu 22.04
vCPU：4
内存：8 GB
系统盘：40 GB
数据盘：50 GB（挂载到 /opt 或 /var/opt，用于 /opt/gitlab）
网卡：桥接或同一内网网段，拿到固定 IP（如 10.0.0.10）
开放端口：80、2222（按需 443）
```

装完系统后：更新软件源 → 安装 Docker → 再进入下文「B：Docker Compose 部署」。

不要和本项目的 `lite-vod` 演示机强行挤在同一台低配机上；GitLab 很吃内存。

## B：Docker Compose 部署 GitLab CE

### 1. 准备目录

```bash
sudo mkdir -p /opt/gitlab/{config,logs,data}
cd /opt/gitlab
```

### 2. 编写 `docker-compose.yml`

把 `gitlab.example.com` 换成你的 IP 或域名（本机可用 `localhost`，但 Runner / 其他机器访问要用可达地址）。

```yaml
services:
  gitlab:
    image: gitlab/gitlab-ce:17.5.1-ce.0
    container_name: gitlab
    hostname: gitlab.example.com
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "2222:22"
    volumes:
      - ./config:/etc/gitlab
      - ./logs:/var/log/gitlab
      - ./data:/var/opt/gitlab
    shm_size: "256m"
    environment:
      GITLAB_OMNIBUS_CONFIG: |
        external_url 'http://gitlab.example.com'
        gitlab_rails['gitlab_shell_ssh_port'] = 2222
```

说明：

- 首次启动可能要 **几分钟**，用 `docker logs -f gitlab` 等到出现可访问提示
- 镜像 tag 可换成更新的 CE 版本；生产请固定版本号
- 仅学习可先只用 HTTP；上公网务必 HTTPS + 防火墙

### 3. 启动并取初始 root 密码

```bash
cd /opt/gitlab
docker compose up -d
# 等待就绪后再执行：
docker exec -it gitlab grep 'Password:' /etc/gitlab/initial_root_password
```

浏览器打开 `http://gitlab.example.com`，用 `root` + 上述密码登录，**立刻改密**。  
初始密码文件约 24 小时后会被删掉，请提前保存。

### 4. 创建 Group / Project（占位）

1. New group：例如 `lite-vod-group`
2. New project：例如 `video`（空仓库即可，下一步再 push 代码）
3. 记下 clone 地址：`http://gitlab.example.com/lite-vod-group/video.git`

## 注册 GitLab Runner（必做）

没有 Runner，`.gitlab-ci.yml` 写了也不会执行。

### 1. 在 GitLab 拿注册令牌

路径（按版本略有差异）：

- 项目级：Settings → CI/CD → Runners → **New project runner**
- 或实例级：Admin → CI/CD → Runners（学习环境可用）

勾选执行器相关能力时，至少能跑 `shell` / `docker`；本流水线推荐 **Docker 执行器**（Job 里用 `image: maven:…`、`docker:24`）。

### 2. 用 Docker 跑 Runner 并注册

```bash
docker run -d --name gitlab-runner --restart unless-stopped \
  -v /srv/gitlab-runner/config:/etc/gitlab-runner \
  -v /var/run/docker.sock:/var/run/docker.sock \
  gitlab/gitlab-runner:latest

docker exec -it gitlab-runner gitlab-runner register
```

交互提示大致填：

| 提示 | 示例 |
| --- | --- |
| GitLab URL | `http://gitlab.example.com` |
| Token | 上一步复制的 runner token |
| Description | `docker-runner-1` |
| Executor | `docker` |
| Default Docker image | `maven:3.9-eclipse-temurin-17` |

挂载宿主机 `docker.sock` 便于部分场景；本仓库 `build_push` 用的是 **DinD**（`docker:24-dind`），还需 Runner / 宿主机允许 privileged，或按公司规范改用 Kaniko。学习机可在 `config.toml` 里为该 runner 打开：

```toml
[[runners]]
  [runners.docker]
    privileged = true
```

改完后：

```bash
docker restart gitlab-runner
```

### 3. 确认 Runner 在线

GitLab → Settings → CI/CD → Runners：状态为 **online**（绿点）。

## A / C 对照

| 若选 | 你要做的 |
| --- | --- |
| **A. 公司实例** | 确认账号、项目权限；确认 Shared / Project Runner 在线且能拉 Docker 镜像 |
| **C. gitlab.com** | 注册账号建项目；用 Shared Runner；Harbor 可换成可公网访问的仓库或后续再接内网 |

## 安全注意（学习机也要守）

- 不要把 `root` 初始密码、Runner token 写进业务仓库
- 公网暴露的 GitLab 必须改默认口令、限制 22/80/443、尽快上 HTTPS
- Runner 权限很大（可执行任意 Job），只注册给信任项目

## 完成标准

- [ ] 已按上表选好虚拟机规格（Demo 建议 4 vCPU / 8 GB）
- [ ] 浏览器能打开 GitLab 并登录（自建或公司实例）
- [ ] 已有 Group / Project（可为空）
- [ ] 至少一台 Runner 状态为 online
- [ ] （自建）知道数据目录在 `/opt/gitlab`（或你的路径），会 `docker compose up -d` 重启

## 本步不做

- 不配高可用 / Geo / 外部 PostgreSQL（学习环境单容器即可）
- 不写业务项目的 `.gitlab-ci.yml`（见 07）
- 不部署 Harbor（见 04）

## 常见问题

| 现象 | 排查 |
| --- | --- |
| 页面一直 502 | 再等几分钟；`docker logs gitlab`；内存是否不足 4 GB |
| 开机后频繁卡住 / OOM | 升到 8 GB，或加 swap；Runner 改到另一台机 |
| 找不到 initial_root_password | 超过 24h 已删，用容器内 `gitlab-rake` 重置（查官方文档） |
| Runner offline | URL 是否 Runner 容器可达；token 是否过期；看 `gitlab-runner` 日志 |
| DinD 权限错误 | `privileged = true`，或改 Kaniko / 宿主机 socket（视安全策略） |
| git clone 要端口 | SSH 映射了 `2222` 时，remote 用 `ssh://git@host:2222/...` |
