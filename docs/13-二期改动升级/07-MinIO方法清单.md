# 07：MinIO 方法清单（首期 + 分片）

> 汇总 Lite VOD **会用到**的 MinIO / S3 API，以及与 `MinioStorage` 的对应关系。  
> 首期封装见 [MinIO-Java-SDK](../MinIO-Java-SDK.md)；分片流程见 [01-分片上传 Multipart](./01-分片上传Multipart.md)。  
> SDK：`io.minio:minio`（当前约 8.5.x）。密钥只用服务端 AccessKey/SecretKey，与 `VOD_PLAY_SECRET` 无关。

## 1. 先记清的约定

| 点 | 说明 |
| --- | --- |
| 一个 `objectKey` | 对应 **一份**最终对象，例如 `raw/{fileId}/source.mp4` |
| Multipart 分片 | **没有**业务可见的 `.../part1` key；片挂在 `(key, uploadId, partNumber)` 下 |
| 谁拼文件 | MinIO 在 `CompleteMultipartUpload` 时按 part 序号字节拼接 |
| 两个 Client | `minioClient`（对内读写）+ `minioPresignClient`（`public-endpoint` 签 URL） |

对象键仍用 `ObjectKeys`，不要手拼。

## 2. 总表：SDK ↔ 封装 ↔ 场景

### 2.1 首期已用（保持）

| SDK 方法 | HTTP / 语义 | 建议 `MinioStorage` | 谁用、干什么 |
| --- | --- | --- | --- |
| `bucketExists` / `makeBucket` | 建桶 | `ensureBucket()` | 启动 / 测试 |
| `uploadObject` / `putObject` | `PUT` 整对象 | `uploadFile(...)` | Worker 回传 HLS、封面 |
| `getPresignedObjectUrl(PUT)` | 签发整对象上传 URL | `presignedPut(key, expiry)` | 小文件整对象直传 |
| `statObject` | `HEAD` | `head()` / `statSize()` | commit 确认对象、写 `size` |
| `getObject` | `GET` | `download()` / `openStream()` | Worker 拉原片；网关流式读 |
| `listObjects` + `removeObject` | 列举 / 删除 | `removePrefix(prefix)` | 删媒资清理 raw/hls/cover |

### 2.2 二期 Multipart（待加到 `MinioStorage`）

| SDK 方法 | HTTP / 语义 | 建议封装名 | 谁用、干什么 |
| --- | --- | --- | --- |
| `createMultipartUpload` | 开会话 → `uploadId` | `createMultipartUpload(key, contentType)` | 申请分片凭证 |
| `getPresignedObjectUrl(PUT)` + query | 签发 **UploadPart** URL | `presignedUploadPart(key, uploadId, partNumber, expiry)` | 浏览器 PUT 每一片 |
| `completeMultipartUpload` | 校验 ETag 列表并拼成最终对象 | `completeMultipart(key, uploadId, parts)` | Complete 接口 |
| `abortMultipartUpload` | 丢弃未完成分片 | `abortMultipart(key, uploadId)` | 失败 / 放弃，防残留计费 |
| `listParts`（可选） | 列出已上传 part | `listParts(key, uploadId)` | 断点续传对齐进度 |

前端 **不**调用 SDK；只 PUT 预签名 URL，并从响应头读 `ETag`。

## 3. 首期方法要点

### 3.1 `presignedPut` — 整对象上传凭证

- 用途：`GET /vod/signature/upload` 返回的唯一 `uploadUrl`
- Client：必须用 **`presignClient`**（Host = `public-endpoint`）
- 注意：预签名通常 **不**绑定额外 `Content-Type`；浏览器乱加未签名头 → 403

```java
presignClient.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(bucket)
                .object(objectKey)
                .expiry(seconds, TimeUnit.SECONDS)
                .build());
```

### 3.2 `statObject` — commit 闸门

- `head(key)`：对象是否存在（Complete 前 Multipart **通常不存在**最终对象）
- `statSize(key)`：写入 `media.size`、校验上限

### 3.3 `uploadObject` — Worker 写回

- 服务端持密钥直传，**不是**预签名
- HLS / 封面务必带正确 `contentType`（见 [Worker-写回MinIO](../Worker-写回MinIO.md)）

### 3.4 `getObject` / `removePrefix`

- 下载原片转码；按 `fileId` 前缀删 raw、hls、cover

## 4. Multipart 方法要点

### 4.1 调用顺序

```text
createMultipartUpload
  →（多次）presigned UploadPart + 客户端 PUT → 收集 ETag
  → completeMultipartUpload
  → 业务 POST /vod/medias（仍用 statObject / head）

失败 / 放弃 → abortMultipartUpload
```

### 4.2 `createMultipartUpload`

```java
CreateMultipartUploadResponse resp = minioClient.createMultipartUpload(
        CreateMultipartUploadArgs.builder()
                .bucket(bucket)
                .object(objectKey)           // 仍是 raw/{fileId}/source.mp4
                .contentType("video/mp4")
                .build());
String uploadId = resp.result().uploadId();
```

返回的 `uploadId` 贯穿后续所有 part / Complete / Abort。

### 4.3 预签名 UploadPart（与整对象 PUT 的差别）

同一 `objectKey`，query 必须带 `uploadId`、`partNumber`：

```java
Map<String, String> q = new HashMap<>();
q.put("uploadId", uploadId);
q.put("partNumber", String.valueOf(partNumber)); // 从 1 开始

presignClient.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(bucket)
                .object(objectKey)
                .expiry(seconds, TimeUnit.SECONDS)
                .extraQueryParams(q)
                .build());
```

- **一次发齐**：初始化时对 `1..N` 循环签发  
- **按需签发**：只存 `uploadId`，某片要传或 URL 过期再签第 `n` 片  
- URL 形态示意：`.../raw/{fileId}/source.mp4?uploadId=...&partNumber=3&X-Amz-...`

### 4.4 `completeMultipartUpload`

客户端（经 API）提交 `parts[{partNumber, etag}]`，ETag **原样**（常含引号）：

```java
minioClient.completeMultipartUpload(
        CompleteMultipartUploadArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .uploadId(uploadId)
                .parts(parts.toArray(Part[]::new))  // Part(partNumber, etag)，按序号升序
                .build());
```

成功后该 `objectKey` 上才有完整对象；之后 commit 与首期相同。

### 4.5 `abortMultipartUpload`

```java
minioClient.abortMultipartUpload(
        AbortMultipartUploadArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .uploadId(uploadId)
                .build());
```

未 Complete 就放弃时必须调；否则未完成分片占空间。可再配桶生命周期清理超龄 multipart。

### 4.6 `listParts`（可选）

服务端或排障时对齐「已上传哪些 part」，辅助断点续传。前端也可用本地进度；二者择一或并用。

## 5. 按业务场景选方法

| 场景 | 用哪些 |
| --- | --- |
| 小文件上传 | `presignedPut` → 客户端 PUT → `statObject`（commit） |
| 大文件上传 | `createMultipartUpload` + `presignedUploadPart` × N → `completeMultipart` → `statObject` |
| 上传失败 / 取消 | `abortMultipartUpload`（可先 `listParts` 确认） |
| 转码读原片 | `getObject` / `download` |
| 转码写 HLS | `uploadObject`（带 Content-Type） |
| 删除媒资 | `listObjects` + `removeObject`（`removePrefix`） |

## 6. 常见坑

| 现象 | 原因 / 处理 |
| --- | --- |
| 预签名 403 | Host 用了内网 `endpoint`；或多加了未签名请求头 |
| Complete 失败 | ETag 被改（少引号）、缺 part、序号乱 |
| commit 4xx | 尚未 Complete，`head` 不到最终对象 |
| 桶空间涨、无对象 | 未 Abort 的 multipart 残留 |
| 与播放密钥混淆 | 上传签名只用 MinIO 密钥，不用 `VOD_PLAY_SECRET` |

## 7. 改造落点（对照代码）

| 位置 | 动作 |
| --- | --- |
| `MinioStorage` | 新增 create / presignPart / complete / abort（及可选 listParts） |
| `UploadSignatureService` | 保留 `create()`；新增 `createMultipart(...)` |
| `MediaController` | multipart 初始化、Complete、Abort 路由 |
| `MediaService.commit` | **可不改**：仍 `head` + `statSize` |

验收与开关见 [01](./01-分片上传Multipart.md)、[06-改造清单](./06-改造清单与兼容策略.md)。
