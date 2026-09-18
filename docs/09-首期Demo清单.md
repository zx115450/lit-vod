# Lite VOD 首期 Demo 清单

> 正式对接天机学堂之前，按本表把闭环演示做完。  
> 对照：[分步实现指南](./05-分步实现指南/00-阅读说明.md)、[实现文档](./04-轻量版云点播-Lite-VOD-实现文档.md) 第 1.3 / 十二 / 十四章。  
> 首期不做：分片上传、多码率、试看 L2、`tj-media` 对接（见步骤 16）。

## 全局成功标准

做完下列 Demo 后，应同时满足：

1. 管理端可直传视频，媒资列表看到「处理完成」
2. 学员端用 `fileId` + 播放凭证，hls.js 能流畅播放
3. 无签名、过期签名、篡改 path / fileId 无法拉到完整流
4. `docker compose` 一键拉起 API / Worker / MinIO / MQ / Nginx / MySQL

---

## Demo 总览

| 编号 | Demo | 对应步骤 | 分期 | 必做 |
| --- | --- | --- | --- | --- |
| D0 | 基础设施可启动 | 01～02 | Phase 0 | 是 |
| D1 | 工程骨架与健康检查 | 03 | Phase 1 | 是 |
| D2 | MinIO 预签名读写 | 04 | Phase 1 | 是 |
| D3 | 上传凭证 → 直传 → commit | 05～06 | Phase 1 | 是 |
| D4 | 媒资查询 | 07 | Phase 1 | 是 |
| D5 | 转码任务投递 | 08 | Phase 2 | 是 |
| D6 | Worker 转码闭环 | 09 | Phase 2 | 是 |
| D7 | 播放签名签发 | 10 | Phase 3 | 是 |
| D8 | 网关验签反代 | 11 | Phase 3 | 是 |
| D9 | hls.js 演示页播放 | 12 | Phase 3 | 是 |
| D10 | 事件通知（Webhook / Pull） | 13 | Phase 2 可选 | 否 |
| D11 | 删除媒资 | 14 | 收尾 | 是 |
| D12 | T1～T8 回归脚本 | 15 | 验收 | 是 |
| D13 | CI/CD 流水线 | 17 | 工程化 | 建议 |

---

## Phase 0：基础设施

### D0 基础设施可启动

- **做什么**：Compose 拉起 MySQL、RabbitMQ、MinIO、Nginx；建 bucket `vod`；手工放一条测试原片；执行 `schema.sql`
- **怎么验**：
  - `docker compose ps` 均为 running / healthy
  - MinIO 控制台可见 `vod/raw/test/source.mp4`（或等价路径）
  - MySQL 中存在 `media`、`media_task` 表
  - `.env` 未进 Git
- **步骤文档**：[01](./05-分步实现指南/01-环境搭建.md)、[02](./05-分步实现指南/02-数据模型初始化.md)

---

## Phase 1：上传与媒资

### D1 工程骨架与健康检查

- **做什么**：`vod-api`、`vod-worker` 空壳进 Compose，能启动
- **怎么验**：`GET /vod/health`（或 actuator）返回 ok；worker 日志出现 started
- **步骤文档**：[03](./05-分步实现指南/03-工程骨架.md)

### D2 MinIO 预签名读写

- **做什么**：封装 `presignedPut` / `head` /（可选）upload、download
- **怎么验**：用预签名 URL `curl -T` 上传小文件，再 `head` 成功；浏览器可达 Host（不是容器内 `minio` 主机名）
- **步骤文档**：[04](./05-分步实现指南/04-对象存储封装.md)

### D3 上传凭证 → 直传 → commit

- **做什么**：
  1. `GET /vod/signature/upload` → `fileId` + 预签名 PUT
  2. 浏览器或 curl 直传 mp4
  3. `POST /vod/medias`：HeadObject 通过后落库
- **怎么验**：
  - 库中 `status` 至少到 `UPLOADED`（接上 D5 后进 `PROCESSING`）
  - 对象不存在时 commit 返回 4xx，不建任务（对应用例 T3）
- **步骤文档**：[05](./05-分步实现指南/05-申请上传凭证.md)、[06](./05-分步实现指南/06-确认上传与媒资落库.md)

### D4 媒资查询

- **做什么**：`GET /vod/medias/{fileId}`、分页列表
- **怎么验**：能查到 filename、status、size；不返回 MinIO 账号或内部 endpoint 当播放地址
- **步骤文档**：[07](./05-分步实现指南/07-媒资查询.md)

---

## Phase 2：转码

### D5 转码任务投递

- **做什么**：commit 成功后写 `media_task`、发 RabbitMQ；`status=PROCESSING`
- **怎么验**：管理台或消费者看到消息 `{fileId, objectKey}`；同一 `fileId` 重复 commit 幂等、不重复建任务（T2）
- **步骤文档**：[08](./05-分步实现指南/08-转码任务投递.md)

### D6 Worker 转码闭环

- **做什么**：Worker 下载原片 → FFmpeg 出 HLS + 封面 → 回传 MinIO → `media=PROCESSED`
- **怎么验**：
  - 桶内存在 `hls/{fileId}/index.m3u8`、若干 `.ts`、`cover/{fileId}.jpg`
  - 详情接口 status=`PROCESSED`，有 duration / cover 路径
  - 损坏文件 → `FAILED` + 可查 `error_msg`（T4）
- **步骤文档**：[09](./05-分步实现指南/09-Worker转码闭环.md)

### D10 事件通知（可选）

- **做什么**：Webhook 回调，或 `PullEvents` + `Confirm` 模拟腾讯事件
- **怎么验**：转码结束能收到一次完成事件；确认后不再重复投递
- **步骤文档**：[13](./05-分步实现指南/13-事件通知.md)

---

## Phase 3：播放与防盗链

### D7 播放签名签发

- **做什么**：`GET /vod/signature/play?fileId=&exper=`，HMAC-SHA256 签发带 `e` / `exper` / `sign` 的 `playUrl`
- **怎么验**：
  - 仅 `PROCESSED` 可签发
  - 固定 secret + 固定输入 → 固定 hex（单测）
  - `VOD_PLAY_SECRET` 只来自环境变量
- **步骤文档**：[10](./05-分步实现指南/10-播放签名签发.md)

### D8 网关验签反代

- **做什么**：Nginx（或 Spring Filter）校验签名后反代 MinIO `vod/hls/`
- **怎么验**：

| 请求 | 期望 |
| --- | --- |
| 无 `sign` / `e` | 401 / 403 |
| 合法 `playUrl` | 200，body 含 `#EXTM3U` |
| `e` 已过期 | 403（T6） |
| 改 path 或 fileId | 403（T7） |
| 同签名拉 `.ts` | 200 |

- **步骤文档**：[11](./05-分步实现指南/11-播放网关验签.md)

### D9 hls.js 演示页播放

- **做什么**：静态页输入 `fileId`（或粘贴 `playUrl`），用 hls.js 播 HLS
- **怎么验**：浏览器能流畅播放；不得直连 MinIO `:9000`；尽量同域减少 CORS
- **步骤文档**：[12](./05-分步实现指南/12-演示页播放验证.md)

---

## 收尾与验收

### D11 删除媒资

- **做什么**：`DELETE /vod/medias/{fileId}`，删库记录 + MinIO 前缀对象
- **怎么验**：详情 404；`raw/`、`hls/`、`cover/` 下该 fileId 对象清空；旧签名再播应为 404（T8）
- **步骤文档**：[14](./05-分步实现指南/14-删除媒资.md)

### D12 T1～T8 回归脚本

- **做什么**：`scripts/e2e.sh`（或 PowerShell / 集成测试）可重复跑通

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| T1 | 上传 → 直传 → commit → 转码 | `PROCESSING` → `PROCESSED` |
| T2 | 重复 commit | 幂等，不重复转码 |
| T3 | 非法对象 commit | 4xx，不建任务 |
| T4 | 损坏文件转码 | `FAILED` + `error_msg` |
| T5 | 合法签名播放 | 200，hls.js 可播 |
| T6 | 过期 sign | 403 |
| T7 | 篡改 path / fileId | 403 |
| T8 | 删除媒资 | 库与对象均清理 |

- **步骤文档**：[15](./05-分步实现指南/15-测试用例落地.md)

### D13 CI/CD 流水线（建议）

- **做什么**：MR / push 自动 `mvn test`；`docker build` 推 Harbor；可选 SSH + Compose 部署演示机；`main` 开分支保护
- **怎么验**：故意改坏单测的 MR 在 GitLab Pipeline 失败且不可合并；Harbor 有 sha tag；仓库无明文密钥
- **步骤文档**：[17](./05-分步实现指南/17-CI与CD流水线.md)

---

## 推荐演示顺序（最短闭环）

把必做 Demo 串成一条可对外演示的路径：

```text
D0 起环境
 → D1～D2 骨架与存储
 → D3～D4 直传并查媒资
 → D5～D6 转码到 PROCESSED
 → D7～D9 签名 + 验签 + 浏览器播放
 → D11 删除收尾
 → D12 跑一遍 T1～T8
 → D13（建议）GitLab CI 跑通 mvn test / 推 Harbor / Compose 部署
```

对外口头演示可压缩为三幕：

1. **上传**：申请凭证 → 直传 → 列表变「处理中 / 完成」
2. **播放**：拿签名 URL → 演示页播放
3. **防盗链**：去掉签名或改过期时间 → 403

---

## 首期明确不做（勿提前开工）

| 项 | 说明 |
| --- | --- |
| 分片上传 Multipart | 大文件二期 |
| 多码率 + master.m3u8 | 首期一档 720p 即可 |
| 试看 L2（preview.m3u8） | 首期 L0 / L1 即可 |
| JWT 播放凭证 | 首期用 HMAC 签名 URL |
| 对接 `tj-media` | 步骤 16，能力闭环后再做 |
| 直播 / DRM / 审核 / CDN | 实现文档非目标 |

详见：[16-增强与对接天机学堂](./05-分步实现指南/16-增强与对接天机学堂.md)
