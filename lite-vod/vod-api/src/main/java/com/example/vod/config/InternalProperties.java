package com.example.vod.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务间内部调用配置（书城 BFF ↔ vod-api）。
 *
 * <p>Header {@code X-Internal-Token} 须与 {@code token} 一致；
 * {@code enabled=false} 时跳过校验（仅本地调试）。
 */
@ConfigurationProperties(prefix = "vod.internal")
public record InternalProperties(
        String token,
        boolean enabled
) {
    public InternalProperties() {
        this("dev-internal-token-change-me", true);
    }

    public String token() {
        return token == null ? "" : token;
    }
}
