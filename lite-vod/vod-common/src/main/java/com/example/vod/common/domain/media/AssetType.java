package com.example.vod.common.domain.media;

/**
 * 媒资资产类型，对应 media.asset_type（VARCHAR，枚举名落库）。
 * <p>缺省 {@link #VIDEO}，保证旧 mp4 行为不变。
 */
public enum AssetType {
    VIDEO("mp4", "video/mp4"),
    DOCUMENT("bin", "application/octet-stream"),
    CHAPTER("md", "text/markdown"),
    IMAGE("jpg", "image/jpeg"),
    AUDIO("mp3", "audio/mpeg"),
    SUBTITLE("vtt", "text/vtt");

    private final String defaultExtension;
    private final String defaultMimeType;

    AssetType(String defaultExtension, String defaultMimeType) {
        this.defaultExtension = defaultExtension;
        this.defaultMimeType = defaultMimeType;
    }

    public String defaultExtension() {
        return defaultExtension;
    }

    public String defaultMimeType() {
        return defaultMimeType;
    }

    /**
     * 解析查询参数；空白或缺省按 {@link #VIDEO}。
     *
     * @throws IllegalArgumentException 未知类型名
     */
    public static AssetType fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return VIDEO;
        }
        return AssetType.valueOf(raw.trim().toUpperCase());
    }

    /** 是否允许经上传凭证创建（CHAPTER 只能由切章 Worker 写回）。 */
    public boolean uploadable() {
        return this != CHAPTER;
    }
}
