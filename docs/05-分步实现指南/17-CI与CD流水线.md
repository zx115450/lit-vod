# 步骤 17：CI 与 CD 流水线

> 分期：工程化收尾（建议在步骤 15 通过后、步骤 16 之前做）。  
> 目标：搭通国内常见链路 —— **GitLab → GitLab CI（`mvn test` + `docker build`）→ 推 Harbor → SSH 在目标机 `compose pull && up -d`**。  
> 上一篇：[测试用例落地](./15-测试用例落地.md)　下一篇：[增强与对接天机学堂](./16-增强与对接天机学堂.md)

## 怎么读

本步已拆成独立文件夹，**按序做完即可**：

**[→ 进入分步目录：17-CI与CD流水线/00-阅读说明.md](./17-CI与CD流水线/00-阅读说明.md)**

| 序号 | 文档 |
| --- | --- |
| 00 | [阅读说明](./17-CI与CD流水线/00-阅读说明.md) |
| 01 | [概念与总览](./17-CI与CD流水线/01-概念与总览.md) |
| 02 | [部署 GitLab 与 Runner](./17-CI与CD流水线/02-部署GitLab与Runner.md) |
| 03 | [仓库与分支保护](./17-CI与CD流水线/03-仓库与分支保护.md) |
| 04 | [准备 Harbor](./17-CI与CD流水线/04-准备Harbor.md) |
| 05 | [配置 CI 变量](./17-CI与CD流水线/05-配置CI变量.md) |
| 06 | [Compose 改为可拉镜像](./17-CI与CD流水线/06-Compose改为可拉镜像.md) |
| 07 | [编写 `.gitlab-ci.yml`](./17-CI与CD流水线/07-编写gitlab-ci.yml.md) |
| 08 | [目标机初始化与部署](./17-CI与CD流水线/08-目标机初始化与部署.md) |
| 09 | [验证、验收与回滚](./17-CI与CD流水线/09-验证验收与回滚.md) |

## 一句话总览

```text
（可选自建）GitLab CE + Runner → feature/* ──MR──► test ──► build&push → Harbor ──►（手动）SSH compose pull/up ──► /vod/health
```

## 选型摘要

| 层 | 选型 |
| --- | --- |
| 代码托管 | GitLab（可自建 CE，或公司实例 / gitlab.com） |
| CI | GitLab CI + Docker 执行器 Runner |
| 镜像仓库 | Harbor |
| 部署 | Docker Compose + SSH |
| 密钥 | CI/CD Variables（Masked）+ 目标机 `.env` |

## 全局完成标准

- [ ] 分步目录 01～09 完成标准均已勾选（含 GitLab / Runner 可用）
- [ ] 能口述 CI / CD / Harbor / 按 tag 回滚
