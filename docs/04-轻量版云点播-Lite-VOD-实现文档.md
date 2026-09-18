# 轻量版云点播（Lite VOD）实现文档

> 目标：用开源组件实现一套「能力对齐腾讯云 VOD 常用子集」的轻量点播服务，可供学习、私有化演示，或作为天机学堂 `tj-media` 的可替换后端。  
> 未做过点播 / 对象存储：先读 [前置知识](./03-点播与对象存储-前置知识.md)。  
> 对照：天机学堂当前视频能力见 [项目熟悉文档](./00-天机学堂-项目熟悉文档.md) 与 `tj-media`（上传签名、播放签名、Procedure 事件回写）。  
> 范围：点播（VOD）；不含直播、DRM、内容审核、多机房调度。

---

## 一、目标与非目标

### 1.1 要对齐的能力

| 能力 | 腾讯云 VOD | Lite VOD 实现 |
| --- | --- | --- |
| 上传凭证 | Upload Signature | MinIO 预签名 URL / 分片上传凭证 |
| 客户端直传 | 前端 → VOD | 前端 → MinIO（不经业务机传文件流） |
| 任务流 | Procedure（转码 + 截封面） | MQ + FFmpeg Worker |
| 事件通知 | PullEvents / 回调 | 任务表状态 + Webhook（可选 Pull 接口） |
| 媒资查询 | DescribeMediaInfos | `GET /vod/medias/{id}` |
| 播放凭证 | Play Signature（JWT） | HMAC 签名播放 URL 或 JWT |
| 试看 | `exper` 试看时长 | Token 携带 `exper`；首期可用业务侧限制 |

### 1.2 明确不做（首期）

- 直播、连麦、实时转码
- 多码率 Abr ladder 智能调度（首期可只出一档 720p HLS）
- 商业级 CDN 调度、全球加速
- DRM、动态水印工单流
- 音视频内容安全审核

### 1.3 成功标准

1. 管理端可直传视频并在媒资列表看到「处理完成」
2. 学员端用 `fileId/mediaId + 播放凭证` 能用 hls.js 流畅播放
3. 未授权 Token 或过期签名无法拉到完整流
4. docker-compose 一键拉起 API / Worker / MinIO / MQ / Nginx

---

可渲染的分图（全景、时序、状态、部署）见 [核心架构图](./06-核心架构图.md)。

## 二、总体架构

```text
┌──────────────┐  1.申请上传凭证   ┌─────────────┐
│  管理端前端  │ ───────────────▶ │  vod-api    │
└──────┬───────┘                  └──────┬──────┘
       │ 2.直传对象                      │ 写 media / 投递任务
       ▼                                 ▼
┌──────────────┐                  ┌─────────────┐     ┌─────────────┐
│    MinIO     │ ◀── 3.读原片 ─── │  vod-worker │────▶│ RabbitMQ /  │
│  (对象存储)  │ ─── 4.写 HLS ──▶ │  (FFmpeg)   │◀────│ Redis Stream│
└──────┬───────┘                  └─────────────┘     └─────────────┘
       │
       │ 5.播放（带签名）
       ▼
┌──────────────┐  校验签名后反代  ┌─────────────┐
│  播放器前端  │ ◀────────────── │ Nginx/Caddy │
└──────────────┘                 └─────────────┘
```

与天机学堂的边界建议：

- **Lite VOD**：存视频、转码、签发播放地址（替代腾讯云 VOD SDK）
- **业务服务（如 tj-media）**：课表鉴权、小节绑定、试看策略、媒资引用统计

首期也可把「业务鉴权 + VOD」合在一个 `vod-api` 里演示；对接天机时再拆。

---

## 三、技术栈

| 层级 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Java 17 + Spring Boot 3（或 Go） | 与现有 Java 技术栈连续；Worker 可用同一语言 |
| 存储 | MinIO | S3 兼容，本地 / 私有化友好 |
| 元数据 | MySQL 8 | `media` / `media_task` |
| 消息 | RabbitMQ | 与天机学堂一致，便于对照；也可用 Redis Stream |
| 转码 | FFmpeg 6.x | Worker 容器内安装 |
| 网关分发 | Nginx | 校验播放签名并反代 MinIO |
| 播放器 | hls.js / Video.js | Web 演示 |
| 部署 | Docker Compose | 五件套：api、worker、minio、mq、nginx、mysql |

资源建议（单机开发）：4 核 8 GB；Worker 与 API 分容器，避免转码拖垮接口。

---

## 四、工程结构建议

```text
lite-vod/
├── docker-compose.yml
├── nginx/
│   └── conf.d/vod.conf
├── sql/
│   └── schema.sql
├── vod-api/                 # Spring Boot：凭证、媒资、播放签名、事件
│   └── src/main/java/.../vod/
│       ├── controller
│       ├── service
│       ├── domain
│       ├── storage          # MinIO 封装
│       └── mq               # 投递转码任务
└── vod-worker/              # 消费任务，调 FFmpeg，回写状态
    └── src/main/java/.../worker/
        ├── consumer
        ├── ffmpeg
        └── storage
```

包名示例：`com.example.vod`。若作为天机旁路演示工程，可放在仓库外独立目录，避免污染 `tjxt` 父工程。

---

## 五、数据模型

### 5.1 表 `media`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint PK | 雪花或自增 |
| file_id | varchar(64) UK | 对外唯一标识（可用 UUID，对齐腾讯 fileId 概念） |
| object_key | varchar(512) | 原始对象键，如 `raw/{fileId}/source.mp4` |
| filename | varchar(255) | 原始文件名 |
| media_url | varchar(512) | 处理后播放入口，如 `hls/{fileId}/index.m3u8` |
| cover_url | varchar(512) | 封面 |
| duration | float | 秒 |
| size | bigint | 原始或成品大小（字节） |
| status | tinyint | 见状态机 |
| error_msg | varchar(512) | 失败原因 |
| create_time / update_time | datetime | — |

### 5.2 表 `media_task`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint PK | — |
| media_id | bigint | 关联 media |
| file_id | varchar(64) | 冗余，便于日志 |
| type | varchar(32) | `TRANSCODE` / `COVER`（可合并为一个 PROCEDURE） |
| status | tinyint | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` |
| attempt | int | 重试次数 |
| error_msg | varchar(512) | — |
| created_at / finished_at | datetime | — |

### 5.3 状态机（media.status）

```text
UPLOADING(0) → UPLOADED(1) → PROCESSING(2) → PROCESSED(3)
                                    └────────→ FAILED(4)
```

幂等：同一 `file_id` 重复 commit 不重复建任务（已 `PROCESSED` 直接返回）。

---

## 六、对象存储路径约定

```text
bucket: vod
  raw/{fileId}/source.mp4          # 原始片
  hls/{fileId}/index.m3u8          # 播放清单
  hls/{fileId}/segment_000.ts
  hls/{fileId}/...
  cover/{fileId}.jpg               # 封面
```

Bucket 策略：公网不开放匿名读；播放一律走 Nginx 签名校验后再反代。

---

## 七、API 设计（对齐 VOD 使用方式）

基础前缀：`/vod`。鉴权：管理端接口需登录；播放签名接口可由业务网关鉴权后调用。

### 7.1 申请上传凭证

- **方法 / 路径**：`GET /vod/signature/upload`
- **响应示例**：

```json
{
  "fileId": "f7c2a1b0e9d84f6a",
  "uploadUrl": "http://minio:9000/vod/raw/...?X-Amz-Algorithm=...",
  "objectKey": "raw/f7c2a1b0e9d84f6a/source.mp4",
  "expireAt": 1710000000
}
```

实现要点：

1. 生成 `fileId`
2. 插入 `media`，`status=UPLOADING`
3. MinIO `presignedPutObject`，有效期建议 30～120 分钟
4. 大文件二期再做 Multipart（`CreateMultipartUpload` + 分片预签名）

### 7.2 确认上传并触发任务流

- **方法 / 路径**：`POST /vod/medias`
- **请求体**：

```json
{
  "fileId": "f7c2a1b0e9d84f6a",
  "filename": "lesson01.mp4"
}
```

处理步骤：

1. HeadObject 校验对象存在
2. 更新 `status=UPLOADED`，写 `filename` / `size`
3. 创建 `media_task`，MQ 发送 `{fileId, objectKey}`
4. `status=PROCESSING`
5. 返回当前 `MediaDTO`

对应天机学堂：`MediaController.saveMedia` + 云侧 Procedure。

### 7.3 查询媒资

- `GET /vod/medias/{fileId}`
- `GET /vod/medias?pageNo=&pageSize=&name=`

### 7.4 申请播放凭证

- **方法 / 路径**：`GET /vod/signature/play`
- **参数**：`fileId`（必填）、`exper`（可选，试看秒数）
- **响应**：

```json
{
  "fileId": "f7c2a1b0e9d84f6a",
  "playUrl": "https://play.example.com/hls/f7c2a1b0e9d84f6a/index.m3u8?sign=...&e=...&exper=300",
  "signature": "可选：若播放器只要 token，可与 playUrl 二选一"
}
```

业务侧（对接天机时）应先做：

1. `sectionId` → `mediaId/fileId`（course）
2. `isLessonValid`（learning）
3. 无课表且非试看 → 403
4. 试看 → 带 `exper` 调本接口

本 VOD 内核**不负责**课表，只负责签发可播放地址。

### 7.5 事件拉取（可选，模拟腾讯 PullEvents）

- `GET /vod/events/pull`：返回已完成未确认的任务事件
- `POST /vod/events/confirm`：传入 eventId 列表确认

也可用 Webhook：`POST {callbackUrl}`，body 含 `fileId`、`status`、`coverUrl`、`duration`。

### 7.6 删除

- `DELETE /vod/medias/{fileId}`：删 MinIO 对象 + 软删/硬删库记录

---

## 八、播放签名算法

### 8.1 推荐：HMAC-SHA256 签名 URL（实现简单）

待签名字符串：

```text
path={uriPath}&e={expireEpoch}&exper={experSeconds|0}
```

示例：

```text
path=/hls/f7c2a1b0e9d84f6a/index.m3u8&e=1710003600&exper=300
```

```text
sign = Hex(HMAC_SHA256(secret, 待签名字符串))
```

完整 URL：

```text
/hls/{fileId}/index.m3u8?e=1710003600&exper=300&sign=abcd...
```

Nginx / 鉴权小服务校验：

1. `e` 未过期
2. 用同样规则重算 `sign` 并恒等比较
3. 通过则 `proxy_pass` 到 MinIO

同目录下 `.ts` 请求：签名可绑定「目录前缀」`/hls/{fileId}/`，避免每个 ts 单独签；或 m3u8 由网关改写为带同参数的 ts URL。

### 8.2 备选：JWT（更接近腾讯 VOD）

Payload 建议字段：`fileId`、`appId`、`currentTimeStamp`、`expireTime`、`exper`、`pcfg`（播放配置名，可忽略）。

播放网关验 JWT 后反代。前端若用云播放器 SDK 需适配；Web 演示用 hls.js 时 **签名 URL 更省事**。

### 8.3 试看（exper）首期策略

| 级别 | 做法 | 安全性 |
| --- | --- | --- |
| L0 | 只签发短 TTL Token，业务告诉播放器限时 | 低（可被绕过） |
| L1 | 网关记录起播时间，超时拒绝后续 ts | 中 |
| L2 | 转码时额外产出 `preview.m3u8`（仅前 N 秒切片） | 较高，推荐二期 |

首期文档默认 **L0 + L1 之一**；与腾讯 `exper` 完全对齐放到二期做 L2。

---

## 九、Worker 转码实现

### 9.1 消费逻辑

```text
1. 收到消息 {fileId, objectKey}
2. 更新 task=RUNNING
3. 从 MinIO 下载到本地临时目录 /tmp/vod/{fileId}/
4. 执行 FFmpeg：HLS + 封面
5. 上传 hls/ 与 cover/
6. ffprobe 取 duration，更新 media=PROCESSED
7. 投递事件 / 回调
8. 清理临时目录
```

失败：写 `FAILED` + `error_msg`；按 `attempt` 有限重试（如最多 3 次）。

### 9.2 FFmpeg 参考命令

单码率 720p HLS：

```bash
ffmpeg -y -i source.mp4 \
  -vf "scale=-2:720" -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 128k \
  -hls_time 6 -hls_list_size 0 -hls_segment_filename "segment_%03d.ts" \
  -f hls index.m3u8
```

封面（第 3 秒）：

```bash
ffmpeg -y -ss 00:00:03 -i source.mp4 -vframes 1 -q:v 2 cover.jpg
```

探测时长：

```bash
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 source.mp4
```

### 9.3 并发与限流

- Worker 并发按 CPU 定，例如每容器 `concurrency=1～2`
- 队列积压时水平加 Worker 副本
- 限制单文件大小（如 2 GB）与时长（如 6 小时），超限直接 FAILED

---

## 十、Nginx 播放网关（示意）

职责：校验 `sign` / `e`，反代 MinIO 的 `vod` bucket。

伪配置要点：

```nginx
location /hls/ {
    # 1. 用 lua / njs / auth_request 校验 sign、e
    auth_request /_auth;
    proxy_pass http://minio:9000/vod/hls/;
    proxy_set_header Host $host;
}
```

`/_auth` 可由旁路小服务（Spring 内嵌 Filter 或 OpenResty）实现 HMAC 校验。开发阶段也可用 Spring Gateway / Filter 代替 Nginx 鉴权，降低运维复杂度。

---

## 十一、与天机学堂对接方式

保持 `tj-media` 门面不变，替换存储实现：

```text
IMediaStorage
  ├─ TencentMediaStorage   # 现有
  └─ LiteVodMediaStorage   # 新：HTTP 调用 lite-vod API
```

| 现有方法 | Lite VOD 映射 |
| --- | --- |
| `getUploadSignature()` | `GET /vod/signature/upload` |
| `getPlaySignature(fileId, userId, freeExpire)` | `GET /vod/signature/play` |
| `queryMediaInfos(fileId)` | `GET /vod/medias/{fileId}` |
| `deleteFile(fileId)` | `DELETE /vod/medias/{fileId}` |
| `PullEventTask` | Webhook → `updateMediaProcedureResult` 或改拉 `/vod/events/pull` |

配置开关示例：

```yaml
tj:
  media:
    platform: lite-vod   # tencent | lite-vod
    lite-vod:
      base-url: http://lite-vod-api:8080
      play-secret: ${VOD_PLAY_SECRET}
```

播放鉴权链路不变：`sectionId` → course → learning → 再向 Lite VOD 要签名。

---

逐步操作（一步一个文档）见 [分步实现指南](./05-分步实现指南/00-阅读说明.md)。

## 十二、分阶段实施计划

### Phase 0：环境（0.5 天）

- [ ] docker-compose 拉起 MinIO、MySQL、RabbitMQ、Nginx
- [ ] 创建 bucket `vod`，写入 schema.sql
- [ ] 本地能 `mc` / 控制台上传一个测试 mp4

### Phase 1：上传与媒资（1～2 天）

- [ ] `GET /vod/signature/upload`
- [ ] 前端或 Postman 直传 MinIO
- [ ] `POST /vod/medias` 落库 `UPLOADED`
- [ ] `GET /vod/medias/{fileId}`

### Phase 2：转码闭环（2～3 天）

- [ ] 定义 MQ 消息体与 `media_task`
- [ ] Worker 下载 → FFmpeg → 上传 HLS/封面 → `PROCESSED`
- [ ] 失败重试与错误信息
- [ ] （可选）Webhook / PullEvents

### Phase 3：播放（1～2 天）

- [ ] 签发签名 URL
- [ ] Nginx/网关验签反代
- [ ] 静态页 + hls.js 验证播放
- [ ] Token 过期拒绝访问

### Phase 4：增强（按需）

- [ ] 分片上传
- [ ] 多码率（360p/720p/1080p）+ master.m3u8
- [ ] 试看 L2（preview 独立切片）
- [ ] 对接 `tj-media` 的 `LiteVodMediaStorage`
- [ ] 指标：上传成功率、转码耗时、播放 4xx 率

---

## 十三、docker-compose 服务清单

| 服务名 | 镜像建议 | 端口 |
| --- | --- | --- |
| mysql | mysql:8 | 3306 |
| rabbitmq | rabbitmq:3-management | 5672 / 15672 |
| minio | minio/minio | 9000 / 9001 |
| vod-api | 自建 | 8080 |
| vod-worker | 自建（含 ffmpeg） | 无对外 |
| nginx | nginx:alpine | 80 |

环境变量（勿提交真实密钥）：

```bash
MINIO_ROOT_USER=...
MINIO_ROOT_PASSWORD=...
VOD_PLAY_SECRET=...
MYSQL_PASSWORD=...
```

Worker Dockerfile 需安装 `ffmpeg`（Debian/Ubuntu 包或静态构建）。

---

## 十四、测试用例清单

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| T1 | 申请上传 → 直传 → commit | media 进入 PROCESSING 再 PROCESSED |
| T2 | 重复 commit 同一 fileId | 幂等，不重复转码 |
| T3 | 非法对象 commit | 4xx，不建任务 |
| T4 | 转码失败（损坏文件） | FAILED，可查 error_msg |
| T5 | 合法签名播放 | 200，hls.js 可播 |
| T6 | 过期 sign | 403 |
| T7 | 篡改 path 或 fileId | 403 |
| T8 | 删除媒资 | 库记录与对象均清理 |

---

## 十五、风险与注意点

1. **版权与内容**：自建点播需自行保证内容合规；本方案不含审核。
2. **CPU 成本**：FFmpeg 吃 CPU，生产要按队列深度扩 Worker，并限制并发。
3. **磁盘**：临时目录与 MinIO 磁盘要监控；转码后及时删本地 raw 缓存。
4. **安全**：`VOD_PLAY_SECRET` 与 MinIO 密钥分开；播放域名与上传域名分离更佳。
5. **不要把密钥写进仓库**：参考天机学堂，敏感项走环境变量 / Nacos。

---

## 十六、参考对照（读代码）

实现时可对照天机学堂现有代码理解「业务期望的接口形态」。完整说明见 [tj-media 实现说明](./07-天机学堂-tj-media实现说明.md)。

| 主题 | 路径 |
| --- | --- |
| 上传 / 播放 API | `tj-media/.../controller/MediaController.java` |
| 播放鉴权（课表 + 试看） | `tj-media/.../service/impl/MediaServiceImpl.java` |
| 腾讯签名实现 | `tj-media/.../storage/tencent/TencentMediaStorage.java` |
| Procedure 事件回写 | `tj-media/.../task/PullEventTask.java` |
| 存储抽象 | `tj-media/.../storage/IMediaStorage.java` |

---

## 十七、文档修订记录

| 日期 | 说明 |
| --- | --- |
| 2026-09-08 | 初稿：轻量 VOD 架构、模型、API、FFmpeg、分期计划与天机对接方式 |
| 2026-09-08 | 增加分步实现指南目录链接 |
| 2026-09-09 | 增加前置知识文档链接 |
| 2026-09-09 | 增加核心架构图文档链接 |
