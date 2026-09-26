package com.example.vod.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 试看 L2 配置：转码侧写 {@code preview.m3u8}；签发侧在试看模式下绑定该 path。
 *
 * <p>{@code seconds}：commit 未传 {@code previewSeconds} 时的默认试看时长。
 * {@code maxSeconds}：上传方可指定的上限。
 * 开关关闭时行为与首期一致。
 */
@ConfigurationProperties(prefix = "vod.preview")
public record PreviewProperties(
        boolean l2Enabled,
        int seconds,
        int maxSeconds
) {

    public static final int DEFAULT_SECONDS = 30;
    public static final int DEFAULT_MAX_SECONDS = 1800;

    public PreviewProperties {
        if (seconds <= 0) {
            seconds = DEFAULT_SECONDS;
        }
        if (maxSeconds <= 0) {
            maxSeconds = DEFAULT_MAX_SECONDS;
        }
        if (maxSeconds < seconds) {
            maxSeconds = seconds;
        }
    }
}
