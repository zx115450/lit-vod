package com.example.vod.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 多码率 ABR 配置，供 vod-worker 与 vod-api 共用。
 *
 * <p>worker 负责按档位转码并写 master.m3u8；api 负责签发 / 验签时指向 master。
 * 开关关闭时行为与首期完全一致：只出单档 {@code hls/{fileId}/index.m3u8}。
 */
@ConfigurationProperties(prefix = "vod.abr")
public record AbrProperties(
        boolean enabled,
        List<Variant> variants
) {

    public List<Variant> variants() {
        return variants == null || variants.isEmpty() ? List.of(
                new Variant("360p", 360, 600_000, 96_000, 800_000),
                new Variant("480p", 480, 1_000_000, 128_000, 1_400_000),
                new Variant("720p", 720, 2_200_000, 128_000, 2_800_000)
        ) : variants;
    }

    /**
     * 一个 ABR 档位。
     *
     * @param label        目录名，如 360p
     * @param height       视频高度（scale=-2:height）
     * @param videoBitrate 目标视频码率（bps）
     * @param audioBitrate 目标音频码率（bps）
     * @param bandwidth    master.m3u8 里声明的 BANDWIDTH（bps），通常略大于 video+audio
     */
    public record Variant(String label, int height, int videoBitrate, int audioBitrate, int bandwidth) {
    }
}
