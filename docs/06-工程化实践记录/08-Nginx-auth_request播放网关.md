# 08：Nginx 反代 + auth_request 播放网关

> 对应求职加深方向 B：[播放网关生产化](../14-求职导向-项目深度提升.md)。  
> 前置：步骤 [10](../05-分步实现指南/10-播放签名签发.md) / [11](../05-分步实现指南/11-播放网关验签.md) / [12](../05-分步实现指南/12-演示页播放验证.md) 已可演示；HMAC 与 Filter 行为清楚。  
> 专题参考：[Nginx 使用说明](../Nginx使用说明.md)、[Nginx 安装包目录说明](../Nginx安装包目录说明.md)。  
> 定位：**配置 + 轻量 Java 验签接口**，不是 Nginx C 模块 / OpenResty 开发。  
> 建议工期：**半天扫盲 + 2～4 天落地**（合计约 3～5 天，与方向 B 一致）。

## 0. 先定边界（读完再动手）

### 0.1 要学什么 / 不必学什么

| 要会 | 不必学 |
| --- | --- |
| `location` / `proxy_pass` / 路径拼接 | Nginx 源码、C 模块 |
| `auth_request`、`internal` 子请求 | OpenResty / Lua（备选了解即可） |
| `nginx -t`、reload、看 `error.log` | 自研 CDN、边缘调度 |
| 把 HMAC 验签留在 Java | 在 Nginx 里重写整套签名算法 |

一句话：**边缘只负责「问一声能不能播 + 去存储取字节」；能不能播仍由 `PlaySignService` 决定。**

### 0.2 现状 → 目标

```text
【已落地】浏览器 → Nginx /hls/
              → auth_request → vod-api /internal/play-auth（只验签，200/403）
              → *.m3u8 → vod-api 改写清单（补 ts 签名 query）
              → *.ts   → Nginx 直反 MinIO（大流量不经 Tomcat）
【本地兜底】无 Nginx 时：`play-sign.gateway-filter-enabled=true`，仍走 PlayGatewayFilter
```

| 项 | Compose / 演示默认 | 本地无 Nginx 兜底 |
| --- | --- | --- |
| `lite-vod/nginx/default.conf` | `auth_request` + m3u8→Java / ts→MinIO | 可不启 nginx |
| 验签 | `GET /internal/play-auth`（`PlayAuthService`） | Filter 内调同一 Service |
| `play-sign.public-base` | `http://localhost`（`VOD_PLAY_PUBLIC_BASE`） | `http://localhost:8080` |
| Filter | `VOD_PLAY_GATEWAY_FILTER_ENABLED=false` | `true`（`application.yml` 默认） |
| MinIO `hls/` | 源站可 GET（供 Nginx 回源）；学员入口仍是 `:80` | Filter 用密钥读桶 |

### 0.3 本篇非目标

- 不引入商业 CDN / DRM
- 不把 HMAC 搬进 njs / Lua（除非你明确想练，不作为验收）
- 不删除 Filter 代码；用开关保留「无 Nginx 本地联调」
- 不在本篇重做签发算法（复用 `PlaySignService` + `PlayPathSupport`）

---

## 1. 半天扫盲：反代与 auth_request

按顺序动手，不必背完整 Nginx 手册。环境：`lite-vod` 目录下已有 `docker compose` 的 `nginx` 服务。

### 1.1 反代在干什么（30～45 分钟）

读懂当前 conf 里已有的两段：

```nginx
location /vod/ {
    proxy_pass http://vod-api:8080/vod/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}

location /hls/ {
    proxy_pass http://vod-api:8080/hls/;
    # ...
}
```

要点：

1. 浏览器只认识 `http://localhost/...`，容器里用主机名 `vod-api`、`minio`
2. `proxy_pass` **末尾有无 `/`** 会改变 URI 替换规则；改前先对照 [Nginx 使用说明 §3.2](../Nginx使用说明.md)
3. 验证：

```bash
cd lite-vod
docker compose exec nginx nginx -t
curl -i http://localhost/vod/health
```

### 1.2 auth_request 在干什么（45～60 分钟）

机制（不必背模块名，记住时序即可）：

```text
客户端请求 /hls/... 
  → Nginx 先发内部子请求到 location = /_auth
  → /_auth 再 proxy_pass 到 Java
  → Java 返回 2xx → 继续主请求（去 MinIO）
  → Java 返回 401/403 → 主请求失败（对客户端通常是 403）
```

最小示意：

```nginx
location /hls/ {
    auth_request /_auth;
    proxy_pass http://minio:9000/vod/hls/;
    proxy_set_header Host minio:9000;
}

location = /_auth {
    internal;   # 禁止浏览器直接访问 /_auth
    proxy_pass http://vod-api:8080/internal/play-auth;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header X-Original-URI $request_uri;
}
```

面试常问：**auth 失败时客户端看到什么？**  
子请求非 2xx 时，Nginx 默认把主请求变成 **403**（可用 `error_page` / `auth_request_set` 定制，首期保持默认即可）。

### 1.3 必懂坑：m3u8 不会自动带签名 query

标准 HLS：playlist 里的相对 `.ts` **不会**继承 playlist URL 上的 `?e&sign`。

现状靠 Filter 里的 `HlsPlaylistRewriter` 改写清单。切到「Nginx 直反 MinIO」后，**若原样返回桶内 m3u8，后续 ts 会无签名 → 全 403**。

本篇推荐的落地策略（业务仍在 Java）：

| 资源 | Nginx 行为 |
| --- | --- |
| `*.m3u8` | `auth_request` 通过后，反代到 **Java 轻量改写接口**（复用 `HlsPlaylistRewriter`） |
| `*.ts` 等媒体 | `auth_request` 通过后，**直反 MinIO**（流量大头） |

这样：鉴权与静态分发已拆开；JVM 只碰小体积清单，不扛整段 TS。

扫盲结束标准：

- [ ] 能口述「反代」与「auth_request」各做一步什么
- [ ] 知道为何不能「全部直反 MinIO、完全不改 m3u8」
- [ ] 能独立执行 `nginx -t` 与 `reload`

---

## 2. 落地总览（建议 4 步，可拆 PR）

| 步 | 内容 | 建议耗时 | 产出 |
| --- | --- | --- | --- |
| A | Java：`/internal/play-auth` + 可选 m3u8 改写接口 | 0.5～1 天 | 单测 + curl 验 200/403 |
| B | Nginx：拆 `m3u8` / `ts` location，挂 `auth_request` | 0.5～1 天 | conf 可 `nginx -t` |
| C | 切换 `public-base`、Filter 开关、Compose 联调 | 0.5～1 天 | 演示页同域可播 |
| D | 验收、文档、面试口述 | 0.5 天 | 本篇完成标准全勾 |

原则：**一步可回滚**；先加接口与 conf，默认开关仍走 Filter，演示通过后再切 `public-base`。

---

## 3. 步骤 A：Java 只验签、不吐片

### 3.1 接口契约（建议）

**验签（给 `auth_request` 用）**

- `GET /internal/play-auth`
- 从请求头读 `X-Original-URI`（完整 path + query，例如 `/hls/{fileId}/segment_000.ts?e=...&exper=...&sign=...`）
- 解析规则与 `PlayGatewayFilter` / `PlayPathSupport` **完全一致**（含试看 L2 白名单逻辑，若项目已开启）
- 成功：`200`，body 可空
- 失败：`403`（缺参、过期、篡改、路径非法、试看越权同 Filter）
- **不要**在此接口读 MinIO 回写媒体字节

可选加固（有余力再做）：

- 仅允许内网 / Compose 网段访问（或 Nginx 所在上游）；不对公网暴露文档化入口
- 不登记到对外 OpenAPI

**清单改写（给 Nginx 反代 m3u8 用）**

二选一即可，推荐 ①：

1. `GET /internal/play-playlist/**` 或复用路径形如 `/hls/.../*.m3u8` 的「仅改写」Controller：验签通过后从 MinIO 读对象 → `HlsPlaylistRewriter` → 写出  
2. 或：Nginx 对 m3u8 仍临时打到现有 Filter，仅把 `.ts` 改走 MinIO（过渡方案，第二阶段再抽纯接口）

正式方案优先 ①，便于关闭 Filter 后行为清晰。

### 3.2 实现要点

1. 从 `PlayGatewayFilter.authorize(...)` 抽出可复用方法（或共享 `PlayAuthService`），避免两套规则漂移  
2. 解析 `X-Original-URI` 时注意：可能含 query；path 部分交给 `PlayPathSupport.parse`  
3. 单测覆盖：无签、过期、篡改 path、合法正片、（若启用）试看 ts 白名单  
4. Filter：增加配置开关，例如 `play-sign.gateway-filter-enabled`（默认 `true` 保兼容）；生产 Compose 设为 `false`

### 3.3 本步验证

```bash
# 假设已签发 PLAY_URL；取出 path?query 部分做 Original-URI
curl -i -H "X-Original-URI: /hls/{fileId}/index.m3u8?e=...&exper=0&sign=..." \
  http://localhost:8080/internal/play-auth
# 期望 200

curl -i -H "X-Original-URI: /hls/{fileId}/index.m3u8" \
  http://localhost:8080/internal/play-auth
# 期望 403
```

完成标准（A）：

- [x] `/internal/play-auth` 与 Filter 验签结果一致  
- [x] 接口不返回媒体 body  
- [x] 有单测；L2 若开启则覆盖 preview 越权  

---

## 4. 步骤 B：改 Nginx conf

文件：`lite-vod/nginx/default.conf`。Compose 已挂载到 `/etc/nginx/conf.d/default.conf`。

### 4.1 推荐 conf 骨架

在保留 `/` 静态页与 `/vod/` 反代的前提下，用正则拆分播放（示意，按你实际上游主机名微调）：

```nginx
# 子请求：只验签
location = /_auth {
    internal;
    proxy_pass http://vod-api:8080/internal/play-auth;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header X-Original-URI $request_uri;
}

# 清单：验签后走 Java 改写（体积小）
location ~ ^/hls/.+\.m3u8$ {
    auth_request /_auth;
    proxy_pass http://vod-api:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    # 若改写接口不是原 /hls/ 路径，这里改成对应 upstream URI
}

# 分片：验签后直反对象存储（流量大）
location ~ ^/hls/.+\.ts$ {
    auth_request /_auth;
    proxy_pass http://minio:9000/vod/hls/;
    proxy_set_header Host minio:9000;
    proxy_hide_header x-amz-id-2;
    proxy_hide_header x-amz-request-id;
}
```

注意：

1. **正则 `location` 与前缀 `location /hls/` 的优先级**要以你最终合并后的 conf 为准；避免留下「整段 `/hls/` 仍打回 Filter」的旧块抢流量  
2. `proxy_pass` 到 MinIO 时核对 path-style：对外 `/hls/{fileId}/x.ts` → 桶内键 `hls/{fileId}/x.ts`（bucket `vod`）  
3. 演示页与 `/hls/` 同域时 CORS 可先不加；跨域再按 [Nginx 使用说明](../Nginx使用说明.md) 谨慎加头  
4. 改完：

```bash
docker compose exec nginx nginx -t
docker compose exec nginx nginx -s reload
```

### 4.2 过渡开关（可选）

若希望「一键切回 Filter」：用两份 conf 或 `include` 片段，Compose 环境变量选挂载文件。首期手动改 conf + reload 也可。

完成标准（B）：

- [x] `default.conf` 已拆 m3u8 / ts / `_auth`（改后请本机 `nginx -t`）  
- [x] 无签名访问 `/hls/...` → 403；学员播放入口是 Nginx `:80`（勿把 `:9000` 当播放地址）  
- [x] 合法签名下，`.ts` 走 Nginx→MinIO；`.m3u8` 走 Java 改写  

> Compose 的 `minio-init` 对 `vod/hls` 前缀开了 download，以便 Nginx 无密钥回源。  
> 演示机请勿把 MinIO 当公网播放入口；生产宜不映射 `:9000` 或改凭证回源。

---

## 5. 步骤 C：切换播放入口

### 5.1 配置

| 配置 | Filter 模式 | Nginx 网关模式（Compose 默认） |
| --- | --- | --- |
| `VOD_PLAY_PUBLIC_BASE` / `play-sign.public-base` | `http://localhost:8080` | `http://localhost` |
| Filter 开关 | `true` | `false`（`VOD_PLAY_GATEWAY_FILTER_ENABLED`） |

签发仍走 `/vod/signature/play`；只是 `playUrl` 的 Host 指向 80 端口。

**切回 Filter（本地无 Nginx）：**

```bash
# 环境变量或改 application.yml
VOD_PLAY_PUBLIC_BASE=http://localhost:8080
VOD_PLAY_GATEWAY_FILTER_ENABLED=true
# 并将 nginx/default.conf 的 /hls/ 改回整段 proxy_pass 到 vod-api，或停掉 nginx 直打 8080
```

### 5.2 联调顺序

1. Compose 起齐：`nginx`、`vod-api`、`minio`（及依赖）  
2. 签发拿到 `playUrl`，确认 Host 为 Nginx  
3. `curl -i "$PLAY_URL"` → 200，body 含 `#EXTM3U`，且媒体行已带 `e`/`sign`  
4. `curl` 某一行 ts URL → 200  
5. 打开 `http://localhost/player.html`（或现有演示页）完整播放  
6. 试看路径（若已做 L2）行为与切网关前一致  

### 5.3 Filter vs auth_request（写进 PR / 实践笔记）

| | Spring Filter | Nginx auth_request |
| --- | --- | --- |
| 适用 | 本地无 Nginx、单进程联调 | 演示 / 接近生产 |
| 大 TS 路径 | 经 Tomcat 堆与线程 | Nginx → 存储 |
| 验签实现 | Java | 仍是 Java（子请求） |
| 运维 | 改代码发版 | 改 conf + reload |

完成标准（C）：

- [x] Compose 已切 `public-base` + 关闭 Filter  
- [x] 错误签名 / 过期 → 403（验签接口与网关）  
- [x] 文档写清如何切回 Filter（见上节）  

---

## 6. 步骤 D：验收与面试

### 6.1 验收清单（对齐方向 B）

- [x] 错误签名 / 过期 → 403；播放入口为 Nginx，不直连桶播片  
- [x] 合法请求下，大 TS 主要走 Nginx→存储，不经 Spring 堆内存托文件  
- [x] m3u8 仍由 Java 改写补签名 query  
- [x] Filter 仍可在无 Nginx 时作为开发开关使用  

补充自测：

```bash
# 无签名
curl -i http://localhost/hls/{fileId}/index.m3u8
# 期望 403

# 合法
curl -i "$PLAY_URL"
# 期望 200

# 篡改 fileId 或 sign
# 期望 403
```

### 6.2 面试口述（30 秒版）

> 开发期用 Spring Filter 验签并回源 MinIO，方便联调。生产把鉴权与静态分发拆开：Nginx `auth_request` 调我们的轻量验签接口，通过后直反对象存储；清单仍由 Java 改写以补齐 ts 签名参数。这样密钥与规则仍在业务侧统一，流量不再打满 API 进程。

追问准备：

- auth 失败客户端为什么常见 403？  
- 为何不把桶开公网只靠「难猜 URL」？  
- m3u8 不改写会发生什么？  
- 和云厂商「签名 URL / 边缘鉴权 + CDN 源站」如何对应？

---

## 7. 常见问题

| 现象 | 排查 |
| --- | --- |
| 一律 403 | Java 是否收到 `X-Original-URI`；query 是否被丢掉；时钟 / `e` 是否过期 |
| m3u8 200 但 ts 全 403 | 改写未生效；或 ts 未走带 `auth_request` 的 location |
| 502 / 404 从 MinIO | `proxy_pass` 路径与 bucket/键不一致；`Host` 头不对 |
| reload 无效 | 改的不是挂载文件；先 `nginx -t`；必要时 `restart nginx` |
| 直打 `:8080/hls` 仍可播 | Filter 仍开且端口暴露；按需关 Filter 或不要映射 8080 到公网 |
| 试看能播正片 ts | 验签接口未复用 L2 白名单，与 Filter 逻辑分叉 |

---

## 8. 相关路径速查

| 路径 | 说明 |
| --- | --- |
| `lite-vod/nginx/default.conf` | 网关 conf（auth_request + 拆 m3u8/ts） |
| `lite-vod/nginx/html/player.html` | 演示页 |
| `.../controller/InternalPlayAuthController.java` | `GET /internal/play-auth` |
| `.../controller/PlayPlaylistController.java` | 网关模式下 m3u8 改写 |
| `.../service/PlayAuthService.java` | Filter / auth_request 共用验签 |
| `.../gateway/PlayGatewayFilter.java` | 本地兜底临时网关 |
| `.../gateway/HlsPlaylistRewriter.java` | m3u8 补 query |
| `.../service/PlaySignService.java` | HMAC 签发 / 校验 |
| `play-sign.public-base` | 播放 URL 对外 Host |
| `play-sign.gateway-filter-enabled` | 是否启用 Filter |

---

## 9. 建议提交方式

- 独立分支 / PR，标题可含 `nginx auth_request play gateway`  
- 勿与 Micrometer、MQ 死信等其它 P0 方向混在同一 PR  
- PR 描述附：切换步骤、回滚（改回 `public-base` + 旧 conf）、验收 curl  

做完后在 [14-求职导向自评](../14-求职导向-项目深度提升.md) 勾选方向 B，并保证简历表述与仓库行为一致。
