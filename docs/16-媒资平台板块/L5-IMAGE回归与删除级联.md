# L5：IMAGE 回归与删除级联

> **阶段**：L5 · **周期**：1～2 天  
> **目标**：IMAGE 上传可走通；删除 DOCUMENT 级联子 CHAPTER；**视频 T1～T8 全量回归**。  
> **上一篇**：[L4 对象签名与内部拉文本](./L4-对象签名与内部拉文本.md)　**下一篇**：[附录-联调样例](./附录-联调样例.md)

---

## 本步要做什么

板块收尾：补齐封面类资产、完善删除语义、证明未破坏首期视频能力。

---

## IMAGE 上传

| 项 | 约定 |
| --- | --- |
| 上传 | `GET /vod/signature/upload?assetType=IMAGE` |
| commit | `assetType=IMAGE`，任务 `THUMBNAIL`（可选） |
| Worker | 压缩/生成 `img/{fileId}/cover.jpg`；失败仍 `FINISHED` 保留原图 |
| 访问 | `GET /vod/signature/object?fileId=` |
| 播放 | `signature/play` → 400 |

首期可简化：THUMBNAIL 失败则 object_key 仍指向原图，status=FINISHED。

---

## 删除 DOCUMENT 级联

`DELETE /vod/medias/{fileId}` 当 `assetType=DOCUMENT`：

```text
1. 若 status=PROCESSING → 409（与视频删除一致）
2. 若书城已绑目录（未来）→ 可选 409；MVP 媒资侧不查书城，直接级联
3. 删除所有 parent_file_id=fileId 的 CHAPTER 行
4. MinIO：removePrefix(chap/{fileId}/) + 删 DOCUMENT 原件
5. 删除父 media 行
```

`CHAPTER` 单独 DELETE：**允许**（管理清理）。只删该章对象与本行，不删父 DOCUMENT、不清整段 `chap/{parent}/`。已写入 `MediaServiceTest.deleteChapterShouldRemoveOnlyThatObject`。

VIDEO 删除：保持 [步骤 14](../05-分步实现指南/14-删除媒资.md) 行为不变。

---

## 视频回归（必须）

对照 [步骤 15](../05-分步实现指南/15-测试用例落地.md) T1～T8：

| 编号 | 场景 |
| --- | --- |
| T1 | 上传凭证 + 直传 |
| T2 | commit → 转码 → FINISHED |
| T3 | 未上传 commit 失败 |
| T4 | 列表/详情 |
| T5 | 播放签名 + hls.js |
| T6 | 试看 exper / preview |
| T7 | 过期签名失败 |
| T8 | 删除媒资 |

DOCUMENT/CHAPTER 改动后 **T1～T8 必须仍绿**。

---

## 板块端到端自测（不依赖书城）

完整脚本见 [附录-联调样例](./附录-联调样例.md)：

```text
1. DOCUMENT 上传 → 切章 → chapters ≥ 3
2. internal content 读第一章
3. play(document) → 400
4. IMAGE 上传 → object 可读
5. DELETE document → 子章与 chap/ 消失
6. mp4 全闭环
```

---

## 完成标准

- [x] IMAGE commit 完成，无 HLS（`THUMBNAIL` → `vod.image.thumbnail`；不写 `media_url`）
- [x] DELETE DOCUMENT 后子 CHAPTER 与 MinIO `chap/` 消失
- [x] 处理中 DELETE → 409
- [ ] 视频 T1～T8 通过（需联调环境跑步骤 15；本步单测已覆盖 VIDEO 的 commit / play / delete，未改转码默认路径）
- [ ] 样例 fileId 已写入附录，可交给书城 B2（联调时手写，勿提交密钥）

> 实现落点：`ImageCommitStrategy` 投递 `ThumbnailTaskMessage`；Worker `ThumbnailConsumer` 生成 `img/{fileId}/cover.jpg`（长边 480），失败仍 `FINISHED` 且 `object_key` 保持原图。
> 删除：DOCUMENT 级联 `deleteByParentFileId` + `removePrefix(chap/)`；CHAPTER 可单独删；IMAGE 额外清 `img/`。AUDIO / SUBTITLE commit 仍 501。

---

## 本步不做

- AUDIO / SUBTITLE 独立类型（501 即可）
- PDF 切章
- 跨库检查书城是否仍引用 fileId
