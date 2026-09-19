package com.example.vod.common.storage;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * MinIO 客户端配置。
 *
 * <ul>
 *   <li>{@code minioClient}：服务端持密钥直连，用于下载 / 上传 / stat / head</li>
 *   <li>{@code minioPresignClient}：用浏览器可访问的 Host 签发预签名 URL，
 *       只在 vod-api 给浏览器上传/播放时用；worker 不注入此 bean</li>
 *   <li>{@code minioAsyncClient}：用于二期 multipart 低级 API（create/complete/abort），
 *       MinIO SDK 8.5.x 的同步封装 MinioClient 未暴露这些方法，只能走 async + join</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    @Primary
    @Qualifier("minioClient")
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    /**
     * 预签名 URL 必须用浏览器能访问的 Host 签发，否则签名里的 host 对不上。
     */
    @Bean
    @Qualifier("minioPresignClient")
    public MinioClient minioPresignClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.effectivePublicEndpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    /**
     * 二期 multipart：MinioClient 未暴露 createMultipartUpload 等低级方法，
     * 需用 MinioAsyncClient（继承 S3Base，有 public async 方法）。
     */
    @Bean
    @Qualifier("minioAsyncClient")
    public MinioAsyncClient minioAsyncClient(MinioProperties props) {
        return MinioAsyncClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }
}
