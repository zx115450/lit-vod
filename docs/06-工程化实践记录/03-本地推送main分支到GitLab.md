# 03：本地推送 main 分支到 GitLab

> 目标：把本地 `f:\java_project\video` 目录首次推送到 GitLab 的 `lite-vod-group/video` 仓库。

## 初始状态

- 本地没有 `.git`
- GitLab 上已有空的 `main` 分支，带默认 README

## 操作步骤

```bash
cd f:\java_project\video
git init -b main

# 调整 .gitignore，不提交运维脚本和工具
git add -A

git -c user.name="video" -c user.email="video@local" commit -m "Initial commit"

# 先拉取远程初始 commit，避免非 fast-forward 被拒绝
git remote add origin http://192.168.150.104:8088/lite-vod-group/video.git
git fetch origin
git pull origin main --allow-unrelated-histories --no-edit

# 保留自己的 README，合并后推送
git push -u origin main
```

## 注意

- 认证用 Personal Access Token：`glpat-<YOUR_GITLAB_TOKEN>`，用户名 `oauth2`。
- `scripts/` 目录含 SSH 密码、Runner token 等，已在 `.gitignore` 中排除，不进入仓库。
- 合并时远程 README 和本地 README 冲突，保留本地项目 README。
