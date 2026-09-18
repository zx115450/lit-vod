package com.example.vod.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 播放签名配置，对应步骤 10。
 *
 * <ul>
 *   <li>{@code secret}        - HMAC 密钥，来自环境变量 {@code VOD_PLAY_SECRET}，与 MinIO 密码分开</li>
 *   <li>{@code public-base}   - 播放 URL 的对外 Host，如 {@code http://localhost}，指向第 11 步的 Nginx</li>
 *   <li>{@code ttl-seconds}   - 签名有效期，建议 1～2 小时</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "play-sign")
public record PlaySignProperties(
        String secret,
        String publicBase,
        long ttlSeconds
) {
    public PlaySignProperties() {
        this("change-me", "http://localhost", 3600L);
    }
}
