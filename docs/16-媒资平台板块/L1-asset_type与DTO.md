# L1：asset_type 与 DTO

> **阶段**：L1 · **周期**：1 天  
> **目标**：`media` 表扩展资产类型；旧 mp4 数据与行为不变；API DTO 透出 `assetType`。  
> **上一篇**：[L0 端口与脚本](./L0-端口与脚本.md)　**下一篇**：[L2 DOCUMENT 上传完成](./L2-DOCUMENT上传完成.md)

---

## 本步要做什么

在现有 `media` / `media_task` 上 **ALTER**，不重建表。所有历史行默认 `VIDEO`，无需手工 UPDATE。

---

## 数据模型

新增 Flyway 或 SQL 脚本（建议 `sql/V2__asset_type.sql`）：

```sql
ALTER TABLE media
  ADD COLUMN asset_type      VARCHAR(16)  NOT NULL DEFAULT 'VIDEO'
    COMMENT 'VIDEO/DOCUMENT/CHAPTER/IMAGE/AUDIO/SUBTITLE' AFTER file_id,
  ADD COLUMN mime_type       VARCHAR(128) NULL AFTER filename,
  ADD COLUMN parent_file_id  VARCHAR(64)  NULL
    COMMENT 'CHAPTER 挂 DOCUMENT' AFTER mime_type,
  ADD COLUMN chapter_no      INT          NULL COMMENT 'CHAPTER 序号',
  ADD COLUMN page_count      INT          NULL,
  ADD COLUMN extract_key     VARCHAR(512) NULL;

CREATE INDEX idx_media_parent ON media (parent_file_id);
```

`media_task.type` 注释扩展（枚举类同步）：`EXTRACT_TEXT`、`SPLIT_CHAPTER`、`THUMBNAIL`。

---

## 代码改动清单

| 模块 | 文件 / 类 | 改动 |
| --- | --- | --- |
| vod-common | `AssetType` 枚举 | `VIDEO, DOCUMENT, CHAPTER, IMAGE, ...` |
| vod-common | `Media` 实体 | 新字段 + getter/setter |
| vod-common | `MediaMapper` | 查询/插入/更新含新列 |
| vod-api | `MediaDto` | `assetType`, `mimeType`, `parentFileId`, `chapterNo`, `pageCount` |
| vod-api | `MediaService.toDto` | 映射新字段 |
| vod-api | 上传凭证 | `GET /vod/signature/upload?assetType=DOCUMENT`（缺省 VIDEO） |

### 上传凭证

申请凭证时根据 `assetType` 决定 `object_key` 前缀（可先统一 `raw/{fileId}/source.{ext}`，L2 再细分 `doc/`）：

- `VIDEO` → 现有逻辑
- `DOCUMENT` → 允许 `.md` / `.txt` / `.pdf`（pdf 首期可仅落库不切章）
- `IMAGE` → `.jpg` / `.png` / `.webp`

创建 `media` 行时写入 `asset_type`、`mime_type`（从 filename 或 Content-Type 推断）。

---

## 行为约束（本步必须保证）

1. **未传 `assetType` 的上传** → 仍按 `VIDEO`，commit 仍走 `PROCEDURE`
2. **旧数据** → `asset_type` 默认 `VIDEO`，列表/详情 DTO 含 `assetType: VIDEO`
3. **JSON 兼容** → 只加字段，旧 Demo 页忽略未知字段即可

---

## 验证

```bash
# 旧 mp4 闭环（回归）
GET /vod/signature/upload
# ... 上传 commit ...
GET /vod/medias/{fileId}
# 期望：assetType=VIDEO，status 流转与改前一致

# 新类型占位（仅凭证 + 落库，L2 才完整 commit）
GET /vod/signature/upload?assetType=DOCUMENT
# commit 后 assetType=DOCUMENT（L2 验收处理中/完成）
```

单元测试：扩展 `MediaServiceTest`，断言 DTO 含 `assetType`；旧测试全部仍绿。

---

## 完成标准

- [x] Flyway/SQL 在 `lite_vod` 库执行成功，旧行 `asset_type=VIDEO`
- [x] `GET /vod/medias/{id}` 响应含 `assetType`
- [x] 不带 `assetType` 的上传 + commit 仍触发 `MediaTaskType.PROCEDURE`
- [x] 相关单测通过（`AssetTypeTest` / `UploadSignatureServiceTest` / `MediaServiceTest` / `MediaControllerTest`）

> 实现落点：`AssetType`、`Media`/`MediaMapper`/`MediaDto`、`UploadSignatureService.create(AssetType)`、`sql/alter_asset_type.sql`（104 已执行）。L2 再做 DOCUMENT 分流。

---

## 本步不做

- DOCUMENT 不走 FFmpeg（L2）
- 不切章、无 `chapters` 接口（L3）
- 对象签名 / internal content（L4）
