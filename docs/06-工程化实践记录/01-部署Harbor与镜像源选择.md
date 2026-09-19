# 01：部署 Harbor 与镜像源选择

> 目标：在 `192.168.150.104` 部署 HTTP Harbor，供 CI 推送镜像。

## 实际配置

- Harbor 版本：`2.12.2`
- 端口：`8089`（因为 80 已被 vod-nginx 占用）
- 管理员：`admin` / `Harbor12345`
- 项目：`lite-vod`
- 安装目录：`/opt/harbor`
- 数据目录：`/data/harbor`

## 关键点

1. **不用 80 端口**：宿主机已有 nginx 监听 80，Harbor HTTP 改到 8089。
2. **HTTP 模式**：内网实验环境，未配 HTTPS。
3. **Docker daemon 加 insecure-registries**：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1ms.run",
    "https://dockerpull.org",
    "https://hub-mirror.c.163.com"
  ],
  "insecure-registries": ["192.168.150.104:8089"]
}
```

4. **用国内镜像源拉 goharbor 组件**：DaoCloud / 1ms 拉取并 `docker tag` 为 `goharbor/*`，再跑 `install.sh`。

## 参考脚本

- `scripts/tmp/deploy_harbor.py`：下载安装包、写配置
- `scripts/tmp/finish_harbor_mirrors.py`：镜像源拉取并安装
- `scripts/tmp/finish_harbor_parallel.py`：并行拉取 Harbor 组件
- `scripts/tmp/harbor_serial_pull.py`：串行兜底拉取

## 使用方式

```bash
docker login 192.168.150.104:8089 -u admin
# 镜像地址
192.168.150.104:8089/lite-vod/vod-api:<tag>
192.168.150.104:8089/lite-vod/vod-worker:<tag>
```
