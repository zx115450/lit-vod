package com.example.vod.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 试看 L2 配置：转码侧写 {@code preview.m3u8}，签发侧在 {@code exper>0} 时绑定该 path。
 *
 * <p>开关关闭时行为与首期一致（仅 exper 字段，不强制换清单）。
 */
@ConfigurationProperties(prefix = "vod.preview")
public record PreviewProperties(
        boolean l2Enabled,
        int seconds
) {

    public static final int DEFAULT_SECONDS = 30;

    public PreviewProperties {
        if (seconds <= 0) {
            seconds = DEFAULT_SECONDS;
        }
    }
}
