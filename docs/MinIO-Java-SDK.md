# MinIO Java SDK 使用说明

> 对应步骤：[04-对象存储封装](./05-分步实现指南/04-对象存储封装.md)。  
> 示例代码：`lite-vod/vod-api` 的 `storage` 包与 `MinioSdkUsageTest`。  
> 协议是 HTTP/HTTPS 上的 Amazon S3 API，不是 FTP 或私有二进制协议。

## 本文解决什么

- 如何创建 `MinioClient`
- 桶、对象键、预签名 URL 分别是什么
- 本项目会用到的 SDK 方法，以及和 `MinioStorage` 的对应关系
- 预签名 PUT 为什么不能乱加 `Content-Type`
- 如何启动 MinIO 并跑通测试

业务上传接口（申请凭证、确认上传）不在本文范围，见步骤 05、06。

## 核心概念

| 术语 | 含义 | 本项目例子 |
| --- | --- | --- |
| Endpoint | MinIO API 地址 | 容器内 `http://minio:9000`，本机 `http://localhost:9000` |
| Bucket | 桶，对象的容器 | `vod` |
| Object Key | 桶内对象名，像路径，其实是字符串 | `raw/{fileId}/source.mp4` |
| Access Key / Secret Key | S3 访问密钥 | 环境变量 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| Presigned URL | 用密钥算出的短时 HTTP 地址 | 返回给浏览器的 `uploadUrl` |

桶不要匿名公开读。谁能写、谁能读，靠服务端密钥或带签名的临时 URL。

对象键约定：

```text
bucket: vod
  raw/{fileId}/source.mp4
  hls/{fileId}/index.m3u8
  hls/{fileId}/segment_000.ts
  cover/{fileId}.jpg
```

不要手拼字符串，用 `ObjectKeys`：

```java
ObjectKeys.raw(fileId);          // raw/{fileId}/source.mp4
ObjectKeys.hlsPlaylist(fileId);  // hls/{fileId}/index.m3u8
ObjectKeys.hlsSegment(fileId, 0); // hls/{fileId}/segment_000.ts
ObjectKeys.cover(fileId);        // cover/{fileId}.jpg
```

## 依赖与配置

`vod-api` 与 `vod-worker` 都引入：

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.17</version>
</dependency>
```

`application.yml`（值全部来自环境变量，仓库内无真实密码）：

```yaml
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  public-endpoint: ${MINIO_PUBLIC_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ROOT_USER:minioadmin}
  secret-key: ${MINIO_ROOT_PASSWORD:minioadmin}
  bucket: ${MINIO_BUCKET:vod}
```

| 配置 | 用途 |
| --- | --- |
| `endpoint` | 服务端 SDK 访问 MinIO（容器里用 `http://minio:9000`） |
| `public-endpoint` | 签发预签名 URL 的 Host（浏览器必须能打开，一般是 `http://localhost:9000`） |

两个 endpoint 往往不同。签名字符串包含 Host，用错 Host 会导致浏览器 PUT 403。

## 创建客户端

不经过 Spring，直接构建：

```java
MinioClient minio = MinioClient.builder()
        .endpoint("http://localhost:9000")
        .credentials("minioadmin", "minioadmin")
        .build();
```

本项目里 `MinioConfig` 注册了两个 Bean：

- `minioClient`：对内读写（`putObject` / `statObject` / `getObject`）
- `minioPresignClient`：只用 `public-endpoint` 签发 URL

密钥只留在服务端。前端拿不到 Access Key，只拿过期的 `uploadUrl`。

## SDK 方法一览

| SDK | HTTP | `MinioStorage` | 谁用 |
| --- | --- | --- | --- |
| `bucketExists` / `makeBucket` | — | `ensureBucket()` | 启动或测试时建桶 |
| `putObject` / `uploadObject` | `PUT` | `uploadFile()` | Worker 回传 HLS、封面 |
| `getPresignedObjectUrl(PUT)` | 签发 `PUT` URL | `presignedPut()` | 申请上传凭证 |
| `statObject` | `HEAD` | `head()` / `statSize()` | 确认上传、写 `media.size` |
| `getObject` | `GET` | `download()` | Worker 拉原片 |
| `listObjects` + `removeObject` | `GET`/`DELETE` | `removePrefix()` | 删除媒资 |

大文件 Multipart 放到步骤 16，本文不做。

## 用法详解

以下片段与 `MinioSdkUsageTest` 一致，可对照阅读。

### 1. 确保桶存在

```java
boolean exists = minio.bucketExists(
        BucketExistsArgs.builder().bucket("vod").build());
if (!exists) {
    minio.makeBucket(MakeBucketArgs.builder().bucket("vod").build());
}
```

Compose 里 `minio-init` 也会执行 `mc mb -p local/vod`，测试里再检查一次更稳妥。

### 2. 服务端直传：`putObject`

带密钥的进程把字节写入对象。Worker 转码后回传切片用这条路，**不是**浏览器上传。

```java
byte[] bytes = "lite-vod-minio-sdk-demo".getBytes(StandardCharsets.UTF_8);
try (InputStream in = new ByteArrayInputStream(bytes)) {
    minio.putObject(PutObjectArgs.builder()
            .bucket("vod")
            .object("raw/dev-test/source.mp4")
            .stream(in, bytes.length, -1)
            .contentType("video/mp4")
            .build());
}
```

本地文件可用 `uploadObject`，传入 `filename` 即可。

### 3. 预签名 PUT：给浏览器的 `uploadUrl`

```java
String uploadUrl = minio.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket("vod")
                .object("raw/dev-test/source.mp4")
                .expiry(1, TimeUnit.HOURS)
                .build());
```

前端（或测试里的 `HttpClient`）再直传，不经过 Spring：

```java
HttpResponse<String> response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build(),
        HttpResponse.BodyHandlers.ofString());
// 期望 200
```

等价的 curl：

```bash
curl -X PUT --upload-file ./tiny.mp4 "$UPLOAD_URL"
```

注意：

- 有效期建议 30～120 分钟，对应返回字段 `expireAt`
- 默认不要给 PUT 再加 `Content-Type`。签发时没把该头算进签名，多一个头就会 403
- 若必须带 `Content-Type`，签发时用 `extraHeaders`，PUT 时带完全相同的头
- `uploadUrl` 的 Host 必须是浏览器能访问的地址，不要签成容器内部的 `http://minio:9000`

### 4. Head 与读大小：`statObject`

```java
try {
    StatObjectResponse stat = minio.statObject(
            StatObjectArgs.builder()
                    .bucket("vod")
                    .object("raw/dev-test/source.mp4")
                    .build());
    long size = stat.size();
} catch (ErrorResponseException e) {
    String code = e.errorResponse().code();
    // NoSuchKey / NoSuchObject 表示对象不存在
}
```

`MinioStorage.head()` 把「不存在」收成 `false`；`statSize()` 返回字节数，供确认上传时写入 `media.size`。

### 5. 下载：`getObject`

```java
try (InputStream in = minio.getObject(
        GetObjectArgs.builder()
                .bucket("vod")
                .object("raw/dev-test/source.mp4")
                .build())) {
    Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
}
```

Worker 转码前按 `objectKey` 把原片拉到本地，再调 FFmpeg。

### 6. 按前缀删除：`listObjects` + `removeObject`

```java
Iterable<Result<Item>> results = minio.listObjects(
        ListObjectsArgs.builder()
                .bucket("vod")
                .prefix("raw/" + fileId + "/")
                .recursive(true)
                .build());
for (Result<Item> result : results) {
    Item item = result.get();
    minio.removeObject(RemoveObjectArgs.builder()
            .bucket("vod")
            .object(item.objectName())
            .build());
}
```

删除媒资时清 `raw/{fileId}/`、`hls/{fileId}/` 和 `cover/{fileId}.jpg`。

## 和业务链路的关系

```text
1. 浏览器  →  vod-api   GET /vod/signature/upload
                 生成 fileId，presignedPut(objectKey) → uploadUrl

2. 浏览器  →  MinIO     PUT uploadUrl
                 文件流不经过 Spring

3. 浏览器  →  vod-api   POST /vod/medias { fileId }
                 head / statSize 确认对象在

4. Worker  →  MinIO     getObject 拉原片，uploadObject 回传 HLS
```

`uploadUrl` 不是 Spring 的上传接口，而是 MinIO 的临时门禁。带宽打在对象存储上，API 只签发凭证、改元数据。

Worker 回传 HLS / 封面的路径约定、Content-Type 与落库顺序：见 [Worker 写回 MinIO 说明](./Worker-写回MinIO.md)。

## 如何验证

先起 MinIO：

```bash
cd lite-vod
docker compose up -d minio
```

控制台：`http://localhost:9001`，默认账号 `minioadmin` / `minioadmin`。

再跑测试（PowerShell 要把 `-Dtest` 整段加引号）：

```bash
cd lite-vod/vod-api
mvn test "-Dtest=MinioSdkUsageTest,ObjectKeysTest,MinioStorageIT"
```

| 测试类 | 作用 |
| --- | --- |
| `ObjectKeysTest` | 不连 MinIO，只验路径约定 |
| `MinioSdkUsageTest` | 不启动 Spring，按顺序演示 SDK |
| `MinioStorageIT` | 验 `presignedPut` / `head` / `statSize` / `download` |

MinIO 未启动时，后两个类会 skip，不影响默认 `mvn test`。

## 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 预签名 PUT 403 | 多带了未签名的头，或 Host 不一致 | 不要擅自加 `Content-Type`；用 `MINIO_PUBLIC_ENDPOINT` 签发 |
| 浏览器打不开 `uploadUrl` | URL 里是 `minio:9000` | 预签名客户端改用 `localhost:9000` 或 Nginx 映射 |
| `statObject` 抛错 | 对象或桶不存在 | 捕获 `NoSuchKey` / `NoSuchObject` / `NoSuchBucket` |
| `mvn test` 全部 skip | 本机 9000 没服务 | `docker compose up -d minio` 后再跑 |
| 桶里没有对象 | 只签了 URL，没 PUT | 用 curl 或测试里的 `HttpClient` 真正上传 |

## 相关文档

- [对象存储封装（步骤 04）](./05-分步实现指南/04-对象存储封装.md)
- [申请上传凭证（步骤 05）](./05-分步实现指南/05-申请上传凭证.md)
- [点播与对象存储：前置知识](./03-点播与对象存储-前置知识.md)
- [官方 MinIO Java SDK](https://min.io/docs/minio/linux/developers/java/API.html)
