package com.example.vod.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 封装：预签名上传、head、stat、下载、写回（带 Content-Type）、清理前缀。
 *
 * <p>api 与 worker 共用。写回 HLS / 封面时必须显式设置 Content-Type，
 * 否则 m3u8 / ts 会被识别为 application/octet-stream，浏览器播放异常。
 */
@Component
public class MinioStorage {

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final MinioProperties props;

    public MinioStorage(
            @Qualifier("minioClient") MinioClient minioClient,
            @Qualifier("minioPresignClient") MinioClient presignClient,
            MinioProperties props
    ) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
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

    public void removePrefix(String prefix) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(props.bucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
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
