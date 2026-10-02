package com.example.vod.common.domain.media;

/**
 * DOCUMENT 切章规则，commit 传入，落库到 media_task.payload 并随 MQ 投递。
 */
public enum SplitRule {
    MARKDOWN,
    TXT_CHAPTER;

    /**
     * 解析请求参数；空白或缺省按 {@link #MARKDOWN}。
     *
     * @throws IllegalArgumentException 未知规则名
     */
    public static SplitRule fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return MARKDOWN;
        }
        return SplitRule.valueOf(raw.trim().toUpperCase());
    }
}
