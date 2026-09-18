# Worker 写回 MinIO 说明

> 对应步骤：[09-Worker 转码闭环](./05-分步实现指南/09-Worker转码闭环.md)。  
> 前置：[MinIO Java SDK 使用说明](./MinIO-Java-SDK.md)、[Java 调用命令行程序使用指南](./Java-ProcessBuilder.md)。  
> 示例封装：`vod-api` 已有 `MinioStorage.uploadFile` / `ObjectKeys`；Worker 侧应复用同一约定（可抽公共模块，或在 `vod-worker` 内复制同名封装）。

## 本文解决什么

- 「写回」指什么：本地转码产物如何变成桶里的对象
- 本地文件名与 `objectKey` 怎么一一对应
- `uploadObject` / `putObject` 怎么写，Content-Type 怎么设
- 写完后库里写什么字段，失败怎么收尾

FFmpeg 怎么调见命令行指南；预签名上传原片见 MinIO SDK 文档。本文只讲 **Worker 把 HLS 与封面推回对象存储**。

## 写回在闭环里的位置

```text
MinIO raw/{fileId}/source.mp4
        │ getObject 下载
        ▼
/tmp/vod/{fileId}/
  source.mp4
  index.m3u8 + segment_*.ts   ← ffmpeg
  cover.jpg                   ← ffmpeg
        │ uploadObject / putObject 写回
        ▼
MinIO hls/{fileId}/* 、 cover/{fileId}.jpg
        │
        ▼
DB：media_url、cover_url、duration、status=PROCESSED
最后删掉 /tmp/vod/{fileId}/
```

本地目录是临时加工区。学员播放时拉的是 MinIO（经播放网关）里的 `hls/...`，不是 Worker 磁盘。

`ffprobe` 读出的时长只写数据库，**不**上传新对象。

## 路径约定

用 `ObjectKeys`，不要手拼：

| 本地文件（工作目录内） | 对象键 | 用途 |
| --- | --- | --- |
| `source.mp4` | `raw/{fileId}/source.mp4` | 仅下载，写回时不改原片 |
| `index.m3u8` | `hls/{fileId}/index.m3u8` | 播放列表 |
| `segment_000.ts` … | `hls/{fileId}/segment_000.ts` … | 切片 |
| `cover.jpg` | `cover/{fileId}.jpg` | 封面 |

```java
ObjectKeys.hlsPlaylist(fileId);   // hls/{fileId}/index.m3u8
ObjectKeys.hlsSegment(fileId, i); // hls/{fileId}/segment_003.ts（i 从 0 起）
ObjectKeys.cover(fileId);         // cover/{fileId}.jpg
ObjectKeys.hlsPrefix(fileId);     // hls/{fileId}/  删除或列举前缀时用
```

落库字段存的是 **对象键字符串**（相对桶），不是 `http://...` 完整 URL：

- `media_url` = `hls/{fileId}/index.m3u8`
- `cover_url` = `cover/{fileId}.jpg`

播放签名下一步再根据 `media_url` 签发可访问地址。

## SDK 两种上传

### 1. 本地文件：`uploadObject`（推荐主路径）

现有封装（未带 Content-Type）：

```117:127:f:\java_project\video\lite-vod\vod-api\src\main\java\com\example\vod\storage\MinioStorage.java
    public void uploadFile(String objectKey, Path localPath) {
        try {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .filename(localPath.toAbsolutePath().toString())
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("upload failed: " + objectKey, e);
        }
    }
```

Worker 写回时建议 **补上 contentType**，否则浏览器 / CDN 可能按 `application/octet-stream` 处理 m3u8，播放异常。

带类型的写法：

```java
minioClient.uploadObject(UploadObjectArgs.builder()
        .bucket(bucket)
        .object(objectKey)
        .filename(localPath.toAbsolutePath().toString())
        .contentType(contentType)
        .build());
```

| 扩展名 | Content-Type |
| --- | --- |
| `.m3u8` | `application/vnd.apple.mpegurl`（或 `application/x-mpegURL`） |
| `.ts` | `video/MP2T` |
| `.jpg` | `image/jpeg` |

这是 **服务端持密钥** 的直传，不是预签名 PUT。不要给上传再套一层浏览器用的 `presignedPut`。

### 2. 流：`putObject`

已知大小、已打开 `InputStream` 时可用：

```java
try (InputStream in = Files.newInputStream(localPath)) {
    minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucket)
            .object(objectKey)
            .stream(in, Files.size(localPath), -1)
            .contentType(contentType)
            .build());
}
```

首期本地文件场景用 `uploadObject` 即可；两种等价，选一种在 `MinioStorage` 里统一。

## 推荐封装：上传整个工作目录产物

思路：扫描工作目录，按文件名映射到 objectKey，循环上传。切片数量随片长变化，不要写死只传 `segment_000.ts`。

```java
public void uploadTranscodeOutputs(String fileId, Path workDir) throws IOException {
    Path playlist = workDir.resolve("index.m3u8");
    Path cover = workDir.resolve("cover.jpg");
    if (!Files.isRegularFile(playlist)) {
        throw new IllegalStateException("missing index.m3u8");
    }
    if (!Files.isRegularFile(cover)) {
        throw new IllegalStateException("missing cover.jpg");
    }

    uploadWithType(
            ObjectKeys.hlsPlaylist(fileId),
            playlist,
            "application/vnd.apple.mpegurl");

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(workDir, "segment_*.ts")) {
        for (Path segment : stream) {
            String name = segment.getFileName().toString();
            // segment_012.ts → 下标 12
            int index = Integer.parseInt(name.substring("segment_".length(), name.length() - ".ts".length()));
            uploadWithType(
                    ObjectKeys.hlsSegment(fileId, index),
                    segment,
                    "video/MP2T");
        }
    }

    uploadWithType(ObjectKeys.cover(fileId), cover, "image/jpeg");
}

private void uploadWithType(String objectKey, Path local, String contentType) {
    try {
        minioClient.uploadObject(UploadObjectArgs.builder()
                .bucket(props.bucket())
                .object(objectKey)
                .filename(local.toAbsolutePath().toString())
                .contentType(contentType)
                .build());
    } catch (Exception e) {
        throw new IllegalStateException("upload failed: " + objectKey, e);
    }
}
```

要点：

- 先确认 `index.m3u8` / `cover.jpg` 存在，再上传；避免半成品进桶却标 `PROCESSED`
- `segment_*.ts` 用目录通配；索引与 `ObjectKeys.hlsSegment` 的 `%03d` 一致
- 上传顺序：可先切片后清单，或先清单后切片；播放前须 **全部** 成功。任一段失败整任务 `FAILED`，不要只写库不重传

也可扩展现有 `uploadFile`：

```java
public void uploadFile(String objectKey, Path localPath, String contentType) { ... }
```

无类型重载留给内部工具文件；对外写回 HLS 一律带类型。

## 和数据库的衔接

上传全部成功后再改媒资，避免「库里已是 PROCESSED、桶里缺切片」：

```text
1. uploadTranscodeOutputs(fileId, workDir)
2. duration = ffprobe(...)
3. UPDATE media SET
     status = 'PROCESSED',
     media_url = 'hls/{fileId}/index.m3u8',
     cover_url = 'cover/{fileId}.jpg',
     duration = ?,
     error_msg = NULL
4. UPDATE task SET status = 'SUCCESS', finished_at = now()
5. 删除 workDir
```

失败（下载 / ffmpeg / 上传任一步）：

- `media.status = FAILED`，`error_msg` 截断到 512
- `task.status = FAILED`
- `attempt < 3` 抛异常让 MQ 重试；超过则 ack
- `finally` 仍删本地临时目录，防止磁盘泄漏

重试时：同一 `fileId` 再次写回会覆盖同名 objectKey，一般可接受。若需干净重跑，可先 `removePrefix(ObjectKeys.hlsPrefix(fileId))` 再上传（首期可选）。

## 和「浏览器预签名上传」的区别

| | 原片上传 | Worker 写回 |
| --- | --- | --- |
| 谁传 | 浏览器 | `vod-worker` |
| 怎么鉴权 | 短时 `presignedPut` URL | 服务端 Access Key，SDK 直连 |
| API | HTTP PUT 到签名 URL | `uploadObject` / `putObject` |
| Content-Type | 预签名时默认别乱加头 | **应显式设置** m3u8 / ts / jpg |
| 对象 | `raw/...` | `hls/...`、`cover/...` |

不要把写回做成「Worker 先找 API 要预签名再 PUT」；Worker 本来就能持密钥访问 MinIO。

## 验证

1. 转码成功后，MinIO 控制台或 `mc ls` 可见：
   - `vod/hls/{fileId}/index.m3u8`
   - 若干 `segment_*.ts`
   - `vod/cover/{fileId}.jpg`
2. `GET /vod/medias/{fileId}`：`status=PROCESSED`，`media_url` / `cover_url` 为上表对象键，`duration > 0`
3. 本机 `/tmp/vod/{fileId}` 已不存在
4. 故意让上传失败（如断 MinIO）：媒资 `FAILED`，且不应出现「仅清单上传成功却标完成」

## 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 能播清单、花屏或缺片 | 只传了 `index.m3u8`，漏了部分 `.ts` | 按通配上传全部 `segment_*.ts` |
| 浏览器下载 m3u8 而非播放 | Content-Type 不对 | 设 `application/vnd.apple.mpegurl` |
| 对象键写成绝对路径 | 误把本地路径当 key | 只用 `ObjectKeys.*` |
| 库已 PROCESSED、桶里没有 | 先改库后上传且中途失败 | 先上传成功再更新状态 |
| 重试后出现旧切片残留 | 上次多传了段数 | 可选先 `removePrefix(hlsPrefix)` |
| Worker 连不上 MinIO | 用了浏览器的 `localhost` endpoint | 容器内用 `http://minio:9000` |

## 本文不做

- 不在 MinIO 上公开读 HLS（交给步骤 10～11 的签名与网关）
- 不实现多码率 `master.m3u8`
- 不改原片 `raw/` 路径

## 相关文档

- [步骤 09：Worker 转码闭环](./05-分步实现指南/09-Worker转码闭环.md)
- [MinIO Java SDK 使用说明](./MinIO-Java-SDK.md)
- [Java 调用命令行程序使用指南](./Java-ProcessBuilder.md)
- [对象存储封装（步骤 04）](./05-分步实现指南/04-对象存储封装.md)
