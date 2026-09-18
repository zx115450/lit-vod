# 06：Compose 改为可拉镜像

> 目标：演示机用 Harbor 镜像名启动，CD 才能 `compose pull`。  
> 上一篇：[配置 CI 变量](./05-配置CI变量.md)　下一篇：[编写 `.gitlab-ci.yml`](./07-编写gitlab-ci.yml.md)

## 本步要做什么

当前仓库里 `lite-vod/docker-compose.yml` 的 `vod-api` / `vod-worker` 是 `build:`，适合本地开发。  
持续部署要用 `pull`，目标机应改为 `image:`，或另备 `docker-compose.prod.yml` 覆盖。

## 目标机写法示意

```yaml
vod-api:
  image: harbor.example.com/lite-vod/vod-api:${IMAGE_TAG:-latest}
  # 去掉 build:，或仅本地开发保留 build

vod-worker:
  image: harbor.example.com/lite-vod/vod-worker:${IMAGE_TAG:-latest}
```

`IMAGE_TAG` 由 CI 写成 commit 短 sha，例如 `a1b2c3d`。

## 目标机目录建议

```text
/opt/lite-vod/
  docker-compose.yml      # 使用 image: 而非 build:
  .env                    # 密钥与 IMAGE_TAG
  nginx/ ...
  sql/ ...
```

## 与本地开发的关系

| 环境 | 建议 |
| --- | --- |
| 开发本机 | 可继续用仓库里带 `build:` 的 compose |
| 演示 / 预发 | 用 `image:` + Harbor；密钥只在机器 `.env` |

可选做法：仓库增加 `docker-compose.prod.yml`，目标机：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
```

## 完成标准

- [ ] 已确定目标机 compose 使用 `image:`（或 prod 覆盖文件已写好）
- [ ] `.env` 中预留 `IMAGE_TAG`（可先填 `latest`）
- [ ] 本地开发 compose 未被误改成「只能 pull、不能 build」（按团队约定）

## 本步不做

- 不要求已能从 Harbor 拉到生产 tag（需步骤 07 先 push）
- 不写完整 `.gitlab-ci.yml`
