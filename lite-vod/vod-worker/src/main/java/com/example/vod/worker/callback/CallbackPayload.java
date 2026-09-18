package com.example.vod.worker.callback;

/**
 * Webhook POST body，与 docs/05-分步实现指南/13-事件通知.md 对齐。
 *
 * <p>{@code status} 取值：{@code PROCESSED} / {@code FAILED}（对外语义，非库内 FINISHED）。
 */
public record CallbackPayload(
        String fileId,
        String status,
        String coverUrl,
        Double duration,
        String errorMsg
) {
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    public static CallbackPayload processed(String fileId, String coverUrl, double duration) {
        return new CallbackPayload(fileId, STATUS_PROCESSED, coverUrl, duration, null);
    }

    public static CallbackPayload failed(String fileId, String errorMsg) {
        return new CallbackPayload(fileId, STATUS_FAILED, null, null, errorMsg);
    }
}
