package com.example.vod.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.CreateMultipartUploadResponse;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import io.minio.messages.Part;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 封装：预签名上传、head、stat、下载、写回（带 Content-Type）、清理前缀、Multipart 分片。
 *
 * <p>api 与 worker 共用。写回 HLS / 封面时必须显式设置 Content-Type，
 * 否则 m3u8 / ts 会被识别为 application/octet-stream，浏览器播放异常。
 * <p>Multipart 方法（create / presignPart / complete / abort）仅 vod-api 使用，
 * 用于二期大文件分片直传。
 */
@Component
public class MinioStorage {

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final MinioAsyncClient asyncClient;
    private final MinioProperties props;

    public MinioStorage(
            @Qualifier("minioClient") MinioClient minioClient,
            @Qualifier("minioPresignClient") MinioClient presignClient,
            @Qualifier("minioAsyncClient") MinioAsyncClient asyncClient,
            MinioProperties props
    ) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
        this.asyncClient = asyncClient;
        this.props = props;
    }

    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(props.bucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("ensure bucket failed: " + props.bucket(), e);
        }
    }

    public String presignedPut(String objectKey, Duration expiry) {
        try {
            int seconds = (int) Math.max(60, Math.min(expiry.toSeconds(), TimeUnit.HOURS.toSeconds(2)));
            return presignClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(props.bucket())
                            .object(objectKey)
                            .expiry(seconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException("presigned put failed: " + objectKey, e);
        }
    }

    /**
     * 签发限时 GET URL，调用方直打 MinIO，字节不过业务进程。
     *
     * <p>expiry 钳制在 1 秒～2 小时（与 {@link #presignedPut} 上限一致）。
     */
    public String presignedGet(String objectKey, Duration expiry) {
        try {
            long raw = expiry == null ? 60L : expiry.toSeconds();
            int seconds = (int) Math.max(1, Math.min(raw, TimeUnit.HOURS.toSeconds(2)));
            return presignClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(props.bucket())
                            .object(objectKey)
                            .expiry(seconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException("presigned get failed: " + objectKey, e);
        }
    }

    // ==================== Multipart（二期） ====================

    /**
     * 开启 multipart 上传会话，返回 uploadId。
     * <p>此时桶内尚无最终对象，分片挂在 (objectKey, uploadId) 下。
     * <p>MinIO SDK 8.5.x 的同步 createMultipartUpload 是 protected，只能走 async + join。
     */
    public String createMultipartUpload(String objectKey, String contentType) {
        try {
            Multimap<String, String> headers = (contentType == null || contentType.isBlank())
                    ? ImmutableMultimap.of()
                    : ImmutableMultimap.of("Content-Type", contentType);
            Multimap<String, String> empty = ImmutableMultimap.of();
            CreateMultipartUploadResponse resp = asyncClient.createMultipartUploadAsync(
                    props.bucket(), null, objectKey, headers, empty).join();
            return resp.result().uploadId();
        } catch (Exception e) {
            throw new IllegalStateException("create multipart failed: " + objectKey, e);
        }
    }

    /**
     * 签发 UploadPart 预签名 URL。
     * <p>与整对象 presignedPut 的差别：query 必须带 uploadId + partNumber（从 1 开始）。
     * 每片的 partNumber 参与签名，因此不能复用同一 URL 传不同片。
     */
    public String presignedUploadPart(String objectKey, String uploadId, int partNumber, Duration expiry) {
        try {
                int seconds = (int) Math.max(60, Math.min(expiry.toSeconds(), TimeUnit.HOURS.toSeconds(2)));
                Map<String, String> query = new HashMap<>();
                query.put("uploadId", uploadId);
                query.put("partNumber", String.valueOf(partNumber));
                return presignClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.PUT)
                                .bucket(props.bucket())
                                .object(objectKey)
                                .expiry(seconds, TimeUnit.SECONDS)
                                .extraQueryParams(query)
                                .build());
        } catch (Exception e) {
            throw new IllegalStateException("presign part failed: " + objectKey + " #" + partNumber, e);
        }
    }

    /**
     * 完成合并：MinIO 按 partNumber 升序用 ETag 校验并拼成最终对象。
     * <p>成功后 objectKey 上才有完整对象，commit 的 HeadObject 才能通过。
     *
     * @param parts 按 partNumber 升序的 (partNumber, etag) 列表，ETag 应原样（含引号）
     */
    public void completeMultipartUpload(String objectKey, String uploadId, List<Part> parts) {
        try {
            Multimap<String, String> empty = ImmutableMultimap.of();
            ObjectWriteResponse resp = asyncClient.completeMultipartUploadAsync(
                    props.bucket(), null, objectKey, uploadId,
                    parts.toArray(Part[]::new), empty, empty).join();
            // resp 不需要额外处理；失败会抛异常
        } catch (Exception e) {
            throw new IllegalStateException("complete multipart failed: " + objectKey, e);
        }
    }

    /**
     * 中止 multipart 上传，丢弃该 uploadId 下未完成分片。
     * <p>未 Complete 就放弃时必须调，否则残留分片占空间、可能计费。
     */
    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            Multimap<String, String> empty = ImmutableMultimap.of();
            asyncClient.abortMultipartUploadAsync(
                    props.bucket(), null, objectKey, uploadId, empty, empty).join();
        } catch (Exception e) {
            throw new IllegalStateException("abort multipart failed: " + objectKey, e);
        }
    }

    public boolean head(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if (isMissing(e)) {
                return false;
            }
            throw new IllegalStateException("head object failed: " + objectKey, e);
        } catch (Exception e) {
            throw new IllegalStateException("head object failed: " + objectKey, e);
        }
    }

    /** 对象是否存在（{@link #head} 别名，签发回退等场景语义更清晰）。 */
    public boolean exists(String objectKey) {
        return head(objectKey);
    }

    public long statSize(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
            return stat.size();
        } catch (Exception e) {
            throw new IllegalStateException("stat size failed: " + objectKey, e);
        }
    }

    /**
     * 下载对象到本地路径，自动创建父目录。
     */
    public void download(String objectKey, Path localPath) {
        try {
            Path parent = localPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = openStream(objectKey)) {
                if (in == null) {
                    throw new IllegalStateException("object not found: " + objectKey);
                }
                Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("download failed: " + objectKey, e);
        }
    }

    /**
     * 打开对象输入流（调用方负责关闭）。对象不存在时返回 {@code null}。
     * 供播放网关流式反代使用，避免先落盘。
     */
    public InputStream openStream(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException e) {
            if (isMissing(e)) {
                return null;
            }
            throw new IllegalStateException("open stream failed: " + objectKey, e);
        } catch (Exception e) {
            throw new IllegalStateException("open stream failed: " + objectKey, e);
        }
    }

    /**
     * 上传本地文件，不指定 Content-Type（MinIO 按扩展名推断）。
     */
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

    /**
     * 上传本地文件并显式指定 Content-Type。Worker 写回 m3u8 / ts / jpg 时用此重载。
     */
    public void uploadFile(String objectKey, Path localPath, String contentType) {
        try {
            minioClient.uploadObject(UploadObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .filename(localPath.toAbsolutePath().toString())
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("upload failed: " + objectKey, e);
        }
    }

    /**
     * 列出指定前缀下所有对象。
     */
    public Iterable<Result<Item>> listObjectsByPrefix(String prefix) {
        try {
            return minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(props.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("list objects failed: " + prefix, e);
        }
    }

    public void removePrefix(String prefix) {
        try {
            Iterable<Result<Item>> results = listObjectsByPrefix(prefix);
            for (Result<Item> result : results) {
                Item item = result.get();
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(props.bucket())
                        .object(item.objectName())
                        .build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("remove prefix failed: " + prefix, e);
        }
    }

    private static boolean isMissing(ErrorResponseException e) {
        String code = e.errorResponse() == null ? "" : e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code);
    }
}
