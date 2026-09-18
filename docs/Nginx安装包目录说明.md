# Nginx 安装包目录说明

> 本文对照 **Windows 官方 zip** 解压后的目录（本机示例：`tools/nginx-1.26.3/`）。  
> 项目运行仍以 Docker 镜像 `nginx:alpine` 为准，见 [Nginx 使用说明](./Nginx使用说明.md)。

## 1. 本机已下载位置

| 项 | 路径 |
| --- | --- |
| 压缩包 | `tools/nginx-1.26.3.zip` |
| 解压目录 | `tools/nginx-1.26.3/` |
| 可执行文件 | `tools/nginx-1.26.3/nginx.exe` |

来源：[nginx.org/download](https://nginx.org/en/download.html)（Windows 稳定版 zip）。

本地试跑（在解压目录下，且 80 端口未被占用）：

```powershell
cd f:\java_project\video\tools\nginx-1.26.3
.\nginx.exe
# 浏览器打开 http://localhost/
.\nginx.exe -s stop
```

与本项目 Compose 同时开时，两边都抢 80 端口，**学习目录结构用本 zip 即可，联调 Lite VOD 请用 `docker compose` 里的 nginx**。

## 2. 顶层目录一览

解压后大致是：

```text
nginx-1.26.3/
├── nginx.exe      # Windows 主程序
├── conf/          # 配置
├── html/          # 默认静态站点
├── logs/          # 访问/错误日志、pid
├── temp/          # 运行时临时文件
├── docs/          # 许可与变更说明
├── contrib/       # 社区贡献工具（非运行必需）
└── .hgtags        # 发行标记（可忽略）
```

| 目录 / 文件 | 作用 | 会不会经常改 |
| --- | --- | --- |
| `nginx.exe` | 启动、停止、重载 Nginx | 否 |
| `conf/` | 所有配置文件 | **是，主要改这里** |
| `html/` | 默认网站根目录（静态页） | 演示页可放这里 |
| `logs/` | `access.log`、`error.log`、`nginx.pid` | 运行时自动写 |
| `temp/` | 代理缓存、上传缓冲等临时目录 | 运行时自动用 |
| `docs/` | LICENSE、CHANGES、README | 只读 |
| `contrib/` | 如 vim 语法高亮、编码转换脚本 | 可选 |

Linux / Docker 官方包习惯路径不同（见第 4 节），但 **逻辑角色相同**：配置、静态页、日志、临时文件分开。

## 3. `conf/` 里有什么

| 文件 | 作用 |
| --- | --- |
| `nginx.conf` | **主配置**：`worker`、`events`、`http { server { ... } }` |
| `mime.types` | 扩展名 → `Content-Type`（如 `.html` → `text/html`） |
| `fastcgi_params` | 反代 PHP-FPM 等 FastCGI 时用的参数模板 |
| `fastcgi.conf` | FastCGI 相关片段（常被 include） |
| `uwsgi_params` / `scgi_params` | uWSGI / SCGI 上游参数 |
| `koi-utf` / `koi-win` / `win-utf` | 旧式字符集映射表，一般不用动 |

Windows 默认 `nginx.conf` 里会有一个 `server { listen 80; ... root html; }`，对应访问 `html/index.html`。

本项目 Docker 方案不直接改这份 Windows conf，而是挂载：

```text
lite-vod/nginx/default.conf  →  容器内 /etc/nginx/conf.d/default.conf
```

等价关系：

| Windows zip | Docker `nginx:alpine`（本项目） |
| --- | --- |
| `conf/nginx.conf` 里的 `server` | 镜像自带主 conf + `conf.d/*.conf` |
| `html/` | `/usr/share/nginx/html` |
| `logs/` | 容器内 `/var/log/nginx`（也常打到 `docker compose logs`） |

## 4. Docker / Linux 常见路径对照

| 角色 | Windows zip | Debian/Ubuntu 包 | 本项目 alpine 容器 |
| --- | --- | --- | --- |
| 主配置 | `conf/nginx.conf` | `/etc/nginx/nginx.conf` | `/etc/nginx/nginx.conf` |
| 站点片段 | 写在主 conf 或自建 | `/etc/nginx/sites-enabled/` | `/etc/nginx/conf.d/` |
| 静态页 | `html/` | `/var/www/html` 等 | `/usr/share/nginx/html` |
| 日志 | `logs/` | `/var/log/nginx/` | `/var/log/nginx/` |
| 可执行文件 | `nginx.exe` | `/usr/sbin/nginx` | `/usr/sbin/nginx` |

记一句：**改行为看 conf；放页面看 html/www；查出错看 logs。**

## 5. 和 Lite VOD 怎么对应

```text
学习用 Windows 包          项目实际运行
─────────────────          ────────────────────────
tools/nginx-1.26.3/   →    docker 服务 nginx:alpine
conf/nginx.conf       →    lite-vod/nginx/default.conf（挂进 conf.d）
html/                 →    可挂 nginx/html 或 API static
proxy 到后端          →    proxy_pass http://vod-api:8080/vod/
```

`proxy_pass` 里的 `vod-api` 是 Compose 的 **服务名**（DNS），不是必须等于 `container_name`；同一网络内用服务名解析即可。

## 6. 常用命令速查（Windows 包）

在 `nginx-1.26.3` 目录执行：

```powershell
.\nginx.exe            # 启动
.\nginx.exe -t         # 检查配置语法
.\nginx.exe -s reload  # 热加载配置
.\nginx.exe -s stop    # 快速停止
.\nginx.exe -s quit    # 优雅退出
```

Docker 联调请用：

```bash
docker compose exec nginx nginx -t
docker compose exec nginx nginx -s reload
```

## 7. 是否提交到 Git

`tools/nginx-*.zip` 与解压目录体积较大，建议只作本机学习，**不要强行提交二进制**。需要时按第 1 节重新下载即可。

## 相关文档

- [Nginx 使用说明](./Nginx使用说明.md)：本项目反代、播放网关、演示页
- [01-环境搭建](./05-分步实现指南/01-环境搭建.md)：Compose 拉起 nginx
