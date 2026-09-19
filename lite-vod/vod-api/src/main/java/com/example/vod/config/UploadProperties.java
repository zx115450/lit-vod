package com.example.vod.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传相关配置。
 *
 * <ul>
 *   <li>{@code multipart-enabled} - 二期分片上传开关，默认关闭。
 *       关闭时 multipart 接口返回 501，前端按文件大小走首期整对象 PUT。</li>
 *   <li>{@code min-part-size}     - 分片大小下限（字节），默认 5MiB，S3 单片下限</li>
 *   <li>{@code max-part-size}     - 分片大小上限（字节），默认 64MiB</li>
 *   <li>{@code default-part-size} - 客户端未指定 partSize 时的默认值，默认 10MiB</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "vod.upload")
public record UploadProperties(
        boolean multipartEnabled,
        long minPartSize,
        long maxPartSize,
        long defaultPartSize
) {
    public UploadProperties() {
        this(false, 5L * 1024 * 1024, 64L * 1024 * 1024, 10L * 1024 * 1024);
    }
}
