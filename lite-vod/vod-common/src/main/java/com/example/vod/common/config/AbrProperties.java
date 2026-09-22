package com.example.vod.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 多码率 ABR 配置，供 vod-worker 与 vod-api 共用。
 *
 * <p>worker 负责按档位转码并写 master.m3u8；api 负责签发 / 验签时指向 master。
 * 开关关闭时行为与首期完全一致：只出单档 {@code hls/{fileId}/index.m3u8}。
 * 渐进式多档：在 ABR 开启基础上，先出 {@code fastVariant} 档并标可播，再异步补齐其余档。
 */
@ConfigurationProperties(prefix = "vod.abr")
public record AbrProperties(
        boolean enabled,
        boolean progressiveEnabled,
        String fastVariant,
        String ladderPreset,
        String fastPreset,
        List<Variant> variants
) {

    public static final String DEFAULT_FAST_VARIANT = "360p";

    public AbrProperties {
        if (fastVariant == null || fastVariant.isBlank()) {
            fastVariant = DEFAULT_FAST_VARIANT;
        }
        if (ladderPreset == null || ladderPreset.isBlank()) {
            ladderPreset = "medium";
        }
        if (fastPreset == null || fastPreset.isBlank()) {
            fastPreset = "veryfast";
        }
    }

    public boolean progressiveEnabled() {
        return enabled() && progressiveEnabled;
    }

    public List<Variant> variants() {
        return variants == null || variants.isEmpty() ? List.of(
                new Variant("360p", 360, 600_000, 96_000, 800_000),
                new Variant("480p", 480, 1_000_000, 128_000, 1_400_000),
                new Variant("720p", 720, 2_200_000, 128_000, 2_800_000)
        ) : variants;
    }

    /**
     * 首档（快路径）档位。
     */
    public Variant fastVariantConfig() {
        String label = fastVariant;
        return variants().stream()
                .filter(v -> v.label().equals(label))
                .findFirst()
                .orElseGet(() -> variants().get(0));
    }

    /**
     * 补档档位，按配置文件顺序，排除首档。
     */
    public List<Variant> ladderVariants() {
        String fastLabel = fastVariant;
        return variants().stream()
                .filter(v -> !v.label().equals(fastLabel))
                .toList();
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
