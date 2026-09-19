# 01：分片上传（S3 Multipart）

> 目标：大文件走 MinIO / S3 Multipart，浏览器直传各分片，业务机仍不中转文件流。  
> 依赖：首期 `raw/{fileId}/source.mp4`、`POST /vod/medias`（HeadObject）不变。  
> SDK 方法对照：[07-MinIO方法清单](./07-MinIO方法清单.md)。  
> 下一篇：[多码率 ABR](./02-多码率ABR.md)

## 1. 为什么要改

| 首期整对象 PUT | 痛点 |
| --- | --- |
| 单次上传 | 大文件易超时、中断后重头传 |
| 无 part 进度 | 难做断点续传与并发加速 |

Multipart 把能力放在对象存储，编排在「客户端 + 服务端凭证」。

## 2. 目标流程

```text
1. 客户端 → API：申请 multipart 上传凭证（可选带 contentLength / partSize）
2. API：生成 fileId，落库 UPLOADING
       MinIO CreateMultipartUpload → uploadId
       为 PartNumber=1..N 签发预签名 UploadPart URL（或按需签发）
3. 客户端：并发 PUT 各分片到 MinIO，收集 ETag
4. 客户端 → API：Complete（uploadId + parts[{partNumber, etag}]）
       或客户端直调 Complete 的预签名（二选一，建议经 API 便于校验）
5. 客户端 → API：POST /vod/medias（commit）— HeadObject 与首期相同
6. 失败 / 放弃：AbortMultipartUpload，避免桶内残留分片计费
```

## 3. 建议 API（可微调路径，保持语义）

### 3.1 初始化

`POST /vod/signature/upload/multipart`

请求示例：

```json
{
  "filename": "lesson01.mp4",
  "contentType": "video/mp4",
  "contentLength": 524288000,
  "partSize": 10485760
}
```

响应示例：

```json
{
  "fileId": "f7c2a1b0e9d84f6a",
  "objectKey": "raw/f7c2a1b0e9d84f6a/source.mp4",
  "uploadId": "MinIoUploadId...",
  "partSize": 10485760,
  "partCount": 50,
  "parts": [
    { "partNumber": 1, "uploadUrl": "https://minio/...&PartNumber=1&UploadId=..." }
  ],
  "expireAt": 1710003600
}
```

说明：

- `partSize` 建议 5MiB～64MiB（S3 单片下限多为 5MiB，最后一片除外）
- part 很多时，可改为「只返回 uploadId，客户端按需 `GET .../parts/{n}/url`」避免超大 JSON

### 3.2 完成合并

`POST /vod/uploads/{fileId}/complete`

```json
{
  "uploadId": "...",
  "parts": [
    { "partNumber": 1, "etag": "\"abcd...\"" }
  ]
}
```

服务端调用 MinIO `CompleteMultipartUpload`。成功后再允许 commit。

### 3.3 中止

`POST /vod/uploads/{fileId}/abort`  
body：`{ "uploadId": "..." }`

### 3.4 commit（不变）

`POST /vod/medias`：`HeadObject` 通过后建任务、发 MQ。  
未 Complete 的对象不存在或不可见，commit 应 4xx。

## 4. 代码改造点（对照首期）

| 位置 | 改动 |
| --- | --- |
| `MinioStorage` | 封装 create / presignPart / complete / abort / listParts |
| `UploadSignatureService` | 保留整对象 `create()`；新增 `createMultipart(...)` |
| `MediaController` | 增加 multipart 相关路由 |
| 表结构（建议） | `media` 可增 `upload_id`、`upload_mode`（SINGLE / MULTIPART）；或独立 `media_upload` 表记 parts |
| 演示页 / 脚本 | 大文件走分片；小文件仍可走首期 PUT |

密钥仍用 **MinIO AccessKey/SecretKey** 签 `X-Amz-*`，与 `VOD_PLAY_SECRET` 无关。

## 5. 兼容策略

- 保留 `GET /vod/signature/upload`（整对象）
- 前端按文件大小分流：例如 `> 20MB` 走 multipart
- Feature flag：`VOD_UPLOAD_MULTIPART_ENABLED=true`

## 6. 验收

- [ ] 100MB+ 文件分片上传成功，commit 后转码到 `FINISHED`
- [ ] 中断后可只重传缺失 part（listParts 或客户端本地进度）
- [ ] Abort 后桶内无该 uploadId 残留分片（或生命周期清理）
- [ ] 未 Complete 就 commit → 4xx，不建转码任务
- [ ] 整对象上传路径回归通过

## 7. 风险

- Complete 前误 commit
- 预签名过期导致部分 part 失败，需刷新 URL
- 额外加 `Content-Type` 等未签名头导致 403（与首期相同坑）
