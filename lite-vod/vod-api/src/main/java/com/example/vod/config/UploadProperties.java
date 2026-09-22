package com.example.vod.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 上传相关配置。
 *
 * <ul>
 *   <li>{@code multipart-enabled} - 二期分片上传开关。
 *       关闭时 multipart 接口返回 501。</li>
 *   <li>{@code min-part-size}     - 分片大小下限（字节），默认 5MiB，S3 单片下限</li>
 *   <li>{@code max-part-size}     - 分片大小上限（字节），默认 64MiB</li>
 *   <li>{@code default-part-size} - 客户端未指定 partSize 时的默认值，默认 10MiB</li>
 * </ul>
 *
 * <p>只保留一个构造方法。额外的无参构造会让 Spring 跳过配置绑定，
 * 开关永远停在构造里写死的 false，环境变量和 application.yml 都不生效。
 */
@ConfigurationProperties(prefix = "vod.upload")
public record UploadProperties(
        @DefaultValue("false") boolean multipartEnabled,
        @DefaultValue("5242880") long minPartSize,
        @DefaultValue("67108864") long maxPartSize,
        @DefaultValue("10485760") long defaultPartSize
) {
}
