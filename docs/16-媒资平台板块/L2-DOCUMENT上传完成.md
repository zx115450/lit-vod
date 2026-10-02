# L2：DOCUMENT 上传完成

> **阶段**：L2 · **周期**：1～2 天  
> **目标**：txt/md 作为 DOCUMENT 上传、commit、落库完成；**不**生成 HLS；播放签名对该类型返回 400。  
> **上一篇**：[L1 asset_type 与 DTO](./L1-asset_type与DTO.md)　**下一篇**：[L3 切章写回](./L3-切章写回.md)

---

## 本步要做什么

扩展 `POST /vod/medias` 请求体，按 `assetType` 分流 Worker 任务。DOCUMENT 首期两种路径：

| 路径 | 行为 |
| --- | --- |
| **A. 仅落库（本步最小）** | commit → `FINISHED`，不投递 PROCEDURE |
| **B. 直接进切章（与 L3 合并）** | commit → 投递 `SPLIT_CHAPTER`，status `PROCESSING` |

推荐：本步先实现 **B 的投递骨架**，切章逻辑在 L3 填 Worker；若 L3 未就绪，Worker 可暂时把 DOCUMENT 标 `FINISHED` 且 `chapters` 为空。

---

## 接口扩展

**commit 请求体**（增量字段，均可选）：

```json
{
  "fileId": "uuid",
  "filename": "redis.md",
  "assetType": "DOCUMENT",
  "splitRule": "MARKDOWN",
  "progressive": null,
  "previewSeconds": null
}
```

- `splitRule`：`MARKDOWN` | `TXT_CHAPTER`，仅 `DOCUMENT` 有效
- 缺省 `assetType` → 仍按 VIDEO 处理

---

## MediaService 分流（策略 + 注册表）

在 `vod-api` 用 **Strategy + Registry** 按 `assetType` 分流，避免 `MediaService.commit` 堆 `if-else`：

| 类 | 职责 |
| --- | --- |
| `CommitStrategy` | 策略接口：`assetType()` + `commit(CommitContext)` |
| `CommitStrategyRegistry` | Spring 注入全部策略 Bean，按类型查找 |
| `VideoCommitStrategy` | `PROCEDURE` → `vod.procedure` |
| `DocumentCommitStrategy` | `SPLIT_CHAPTER` → `vod.document.split`，写 `payload` |
| `ChapterCommitStrategy` | 400，禁止直传 |
| `ImageCommitStrategy` | 501（L5） |

分流语义：

```text
assetType == VIDEO
  → MediaTaskType.PROCEDURE → vod.procedure 队列

assetType == DOCUMENT
  → MediaTaskType.SPLIT_CHAPTER → vod.document.split 队列（新建）
  → 禁止创建 PROCEDURE 任务
  → media.status = PROCESSING

assetType == IMAGE
  → L5 再实现；本步 501

assetType == CHAPTER
  → 400，CHAPTER 只能由切章 Worker 创建，禁止直传 commit
```

**关键**：DOCUMENT commit 后 `media_url` 保持 null，**不**写 `hls/.../index.m3u8`。

---

## RabbitMQ

新增队列（与 FFmpeg 隔离）：

| 项 | 值 |
| --- | --- |
| 队列名 | `vod.document.split` |
| 并发 | Worker 侧 1（避免与转码抢 CPU） |
| 消息体 | `{ fileId, splitRule, objectKey }` |

在 `RabbitConfig` 中声明队列；`ProcedureConsumer` **不要**消费 DOCUMENT 消息。

---

## PlayAuth 拦截

`GET /vod/signature/play?fileId=`：

- `assetType == VIDEO` → 现有逻辑
- 其他类型 → **400**，body 如 `play signature only for VIDEO`

---

## 验证

准备 `samples/redis-preview.md`（至少 3 个 `##` 标题，L3 切章用）。

```bash
1. GET /vod/signature/upload?assetType=DOCUMENT
2. PUT 预签名 URL，body 为 md 文件
3. POST /vod/medias  { fileId, filename, assetType: DOCUMENT, splitRule: MARKDOWN }
4. GET /vod/medias/{fileId}
   → status=PROCESSING 或 FINISHED（视 Worker 是否已实现 L3）
   → 无 media_url / 无 hls 前缀
5. GET /vod/signature/play?fileId={documentFileId} → 400
6. 再走一条 mp4，确认仍 PROCEDURE + 可播
```

---

## 完成标准

- [x] DOCUMENT commit 不创建 PROCEDURE、不投递 `vod.procedure`
- [x] MinIO 中无该 fileId 的 `hls/` 目录（commit 不写 `media_url`）
- [x] `GET /vod/signature/play` 对 DOCUMENT 返回 400
- [x] VIDEO commit 行为与 L1 后一致（`VideoCommitStrategy`）
- [x] `splitRule` 写入 `media_task.payload` 并随 `DocumentSplitTaskMessage` 投递

> 实现落点：`CommitStrategy` + `CommitStrategyRegistry`（VIDEO / DOCUMENT / CHAPTER / IMAGE）；
> 队列 `vod.document.split`；Worker `DocumentSplitConsumer`（L2 骨架标 FINISHED，L3 填切章）。
> SQL：`sql/alter_media_task_payload.sql`（104 需执行）。

---

## 本步不做

- 切章算法与 `chapters` 接口（L3）
- PDF `EXTRACT_TEXT`（二期；可 commit 后 FINISHED + 空 chapters）
- 对象签名（L4）
