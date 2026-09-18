# Nginx 使用说明（Lite VOD）

> 对应步骤：[01-环境搭建](./05-分步实现指南/01-环境搭建.md)、[11-播放网关验签](./05-分步实现指南/11-播放网关验签.md)、[12-演示页播放验证](./05-分步实现指南/12-演示页播放验证.md)。  
> 配置文件：`lite-vod/nginx/default.conf`；编排：`lite-vod/docker-compose.yml` 中的 `nginx` 服务。

## 本文解决什么

- Nginx 在本项目里扮演什么角色
- Compose 里如何启动、挂载配置
- 当前 `default.conf` 每一段在做什么
- 步骤 11 目标形态（`auth_request` + 反代 MinIO）怎么配
- 与临时 Spring Filter 方案如何并存、如何切换
- 演示页、CORS、`playUrl` 公网基址等常见坑

HMAC 算法本身见 [密码学-开发实用指南](./密码学-开发实用指南.md) 与步骤 10；本文只讲网关与反代。

## 1. 它在架构里干什么

Nginx 是 **对外统一入口**（本机默认 `http://localhost:80`），不负责转码、不落库。

| 职责 | 说明 |
| --- | --- |
| 静态资源 | 托管演示页 `player.html` 等 |
| 反代 API | `/vod/**` → `vod-api:8080` |
| 播放网关（目标） | `/hls/**` 验签后反代 MinIO；学员不直连桶 |
| TLS / 限流等 | 首期可不做；生产可再加 |

推荐流量（正式方案）：

```text
浏览器
  ├─ /              → Nginx 静态页
  ├─ /vod/...       → Nginx → vod-api（签发、媒资等）
  └─ /hls/...?e&sign
                    → Nginx auth_request → vod-api 验签
                    → 通过后 Nginx → MinIO 取 m3u8 / ts
```

临时开发方案（步骤 11 已允许）：播放走 `vod-api` 的 `PlayGatewayFilter`，Nginx 仍可只做静态页 + `/vod/` 反代。

## 2. Compose 里怎么用

`docker-compose.yml` 要点：

| 项 | 值 |
| --- | --- |
| 镜像 | `nginx:alpine` |
| 端口 | `80:80` |
| 配置挂载 | `./nginx/default.conf` → `/etc/nginx/conf.d/default.conf:ro` |
| 依赖 | `vod-api`（保证反代上游存在） |
| 网络 | `lite-vod`（可用主机名 `vod-api`、`minio`） |

常用命令（在 `lite-vod` 目录）：

```bash
docker compose up -d nginx
docker compose logs -f nginx
docker compose exec nginx nginx -t          # 检查配置语法
docker compose exec nginx nginx -s reload   # 改 conf 后热加载
```

改了宿主机上的 `default.conf` 后，因是挂载文件，一般 `reload` 即可；若未生效再 `docker compose restart nginx`。

## 3. 当前配置解读

仓库里现有配置大致如下：

```nginx
server {
    listen 80;
    server_name localhost;

    location / {
        root /usr/share/nginx/html;
        index index.html index.htm;
    }

    location /vod/ {
        proxy_pass http://vod-api:8080/vod/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 3.1 `location /`：静态站

- 容器内默认根目录：`/usr/share/nginx/html`
- 演示页可二选一：
  - 挂卷：把本机 `nginx/html/player.html` 挂到上述目录（需在 Compose 增加 volume）
  - 或放到 `vod-api` 的 `classpath:/static/player.html`，经 `/vod/` 以外路径由 API 提供（见步骤 12）

### 3.2 `location /vod/`：反代业务 API

- 浏览器访问：`http://localhost/vod/signature/play?...`
- Nginx 转到：`http://vod-api:8080/vod/signature/play?...`
- `proxy_set_header Host`：让上游看到对外 Host，便于日志与部分校验
- `X-Real-IP`：传递客户端真实 IP

注意：`proxy_pass` 末尾带 URI 前缀 `/vod/` 时，会按「替换 location 前缀」规则拼接，需与上游 context-path 一致，避免多一层或少一层 `/vod`。

### 3.3 尚未写入 conf 的部分

`/hls/` 播放验签 **尚未** 出现在当前 `default.conf` 中。开发阶段可用 Spring Filter；切正式方案时按下一节追加。

## 4. 目标：播放网关（步骤 11）

公网不开放 MinIO 匿名读。播放请求必须带合法 `e`、`sign`。

### 4.1 推荐：`auth_request` + Java 验签

```nginx
location /hls/ {
    auth_request /_auth;
    proxy_pass http://minio:9000/vod/hls/;
    proxy_set_header Host minio:9000;
    proxy_hide_header x-amz-id-2;
    proxy_hide_header x-amz-request-id;

    # 演示页若与播放不同源，按需打开 CORS（签名在 query，勿与 cookie + * 混用）
    # add_header Access-Control-Allow-Origin *;
    # add_header Access-Control-Allow-Methods "GET, HEAD, OPTIONS";
}

location = /_auth {
    internal;
    proxy_pass http://vod-api:8080/internal/play-auth;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header X-Original-URI $request_uri;
}
```

含义：

1. 浏览器请求 `/hls/{fileId}/index.m3u8?e&exper&sign`
2. Nginx 先内部请求 `/_auth` → Java `GET /internal/play-auth`
3. Java 根据 `X-Original-URI` 验 HMAC，返回 `200` 或 `403`
4. 仅当 200 时，Nginx 才 `proxy_pass` 到 MinIO 取对象

路径拼接：对外 `/hls/xxx` → 桶内对象键一般是 `hls/xxx`（bucket 名 `vod` 在 MinIO path-style 里常体现为 `/vod/hls/...`，以你实际 MinIO 访问风格为准，联调时用日志核对）。

### 4.2 m3u8 与 ts 的签名约定

步骤 10 签发绑定的是完整 playlist 路径（如 `/hls/{fileId}/index.m3u8`）。  
相对路径的 `.ts` **不会**自动带上 playlist 的 query，因此：

- 正式 Nginx 方案：要么由上游改写 m3u8 补 query，要么验签改为「目录前缀 + 同一套 e/sign」
- 当前 Spring Filter 方案：已在 Filter 内改写 m3u8

签发与验签必须同一约定，详见步骤 10、11。

### 4.3 其它选型（了解即可）

| 方案 | 适用 |
| --- | --- |
| `auth_request` + 旁路 Java | 与签发算法完全复用，**推荐** |
| OpenResty / njs 内嵌 HMAC | 少一次跳转，运维与密钥分发更重 |
| Spring Filter 代发片源 | 开发最快；流量经 Tomcat，不宜当生产主路径 |

## 5. 与 Spring Filter 如何切换

| 阶段 | `VOD_PLAY_PUBLIC_BASE` 示例 | 播放流量 |
| --- | --- | --- |
| 临时 Filter | `http://localhost:8080` | 直打 api，`PlayGatewayFilter` 验签并读 MinIO |
| Nginx 网关 | `http://localhost` | 打 80 端口 `/hls/`，片源不经 Tomcat |

切换检查清单：

- [ ] `default.conf` 已加 `/hls/` + `/_auth`
- [ ] `vod-api` 已提供 `/internal/play-auth`（或等价）
- [ ] `public-base` 改为 Nginx 入口
- [ ] MinIO 仍不对学员匿名读
- [ ] 演示页与 `/hls/` 尽量同域，减少 CORS
- [ ] curl 无签 403、合法 playUrl 200、篡改 403

## 6. 演示页怎么挂

步骤 12 要求：输入 `fileId` → 调签发接口 → hls.js 播 `playUrl`。

两种常见挂法：

**A. Nginx 静态目录（推荐与播放同域）**

```text
lite-vod/nginx/html/player.html
```

Compose 增加（示例）：

```yaml
volumes:
  - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
  - ./nginx/html:/usr/share/nginx/html:ro
```

浏览器打开：`http://localhost/player.html`。

**B. 由 Spring 提供静态资源**

放到 `vod-api/src/main/resources/static/player.html`，访问 `http://localhost:8080/player.html` 或经 Nginx 另配 location。若播放仍走 `:8080/hls/`，注意跨端口 CORS。

## 7. 关键坑

| 现象 | 可能原因 |
| --- | --- |
| `/vod/` 404 或路径错一层 | `proxy_pass` 是否带尾部 `/`、与 location 替换规则不一致 |
| 能签发不能播 | `public-base` 仍指向未开 `/hls/` 的入口；或仍直连 MinIO 被拒 |
| ts 全 403 | m3u8 未改写 query；或验签 path 与签发不一致 |
| 浏览器 CORS 报错 | 演示页与 `/hls/` 不同源，未加 CORS 头 |
| 容器内 `minio` 主机名浏览器打不开 | 浏览器只能访问宿主机映射端口；反代应在 Nginx/API 侧用容器名 |
| reload 不生效 | 改错文件、挂载路径不对、或语法错误（先 `nginx -t`） |

本地快速验：

```bash
# API 经 Nginx
curl -i http://localhost/vod/health

# 播放（正式方案配好后）
curl -i "http://localhost/hls/{fileId}/index.m3u8"
# 期望无签 401/403

curl -i "$PLAY_URL"
# 期望 200，body 含 #EXTM3U
```

## 8. 和本项目其它组件的边界

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| Nginx | 入口、反代、静态、（目标）播前验签调度 | 转码、HMAC 实现细节（交给 Java） |
| vod-api | 签发、验签接口、媒资 API | 长期扛全量 ts 流量（生产） |
| MinIO | 存 raw/hls/cover | 对公网匿名读 |
| vod-worker | 转码写回 | HTTP 入口 |

## 9. 建议学习顺序

1. 读懂并改通当前 `default.conf` 的 `/` 与 `/vod/`
2. 用 Compose 挂上演示页，走通步骤 12（可先 Filter 播放）
3. 实现 `/internal/play-auth`，把 `/hls/` 段写进 Nginx，改 `public-base`
4. 对照步骤 11 完成标准做 T5～T7

## 10. 相关文档

| 文档 | 内容 |
| --- | --- |
| [Nginx 安装包目录说明](./Nginx安装包目录说明.md) | Windows zip / Docker 各目录含义 |
| [01-环境搭建](./05-分步实现指南/01-环境搭建.md) | Compose 拉起含 Nginx |
| [11-播放网关验签](./05-分步实现指南/11-播放网关验签.md) | 验签规则与示意 conf |
| [12-演示页播放验证](./05-分步实现指南/12-演示页播放验证.md) | player 页行为 |
| [10-播放签名签发](./05-分步实现指南/10-播放签名签发.md) | `playUrl` 与 `public-base` |
| [密码学-开发实用指南](./密码学-开发实用指南.md) | HMAC 与签名串 |
