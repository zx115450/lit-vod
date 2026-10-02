package com.example.vod.worker.callback;

/**
 * Webhook POST body，与 docs/05-分步实现指南/13-事件通知.md 对齐。
 *
 * <p>{@code status} 取值：{@code PROCESSED} / {@code FAILED}（对外语义，非库内 FINISHED）。
 * <p>{@code eventType}：{@code PROCEDURE}（视频转码）/ {@code DOCUMENT_SPLIT}（文档切章）。
 */
public record CallbackPayload(
        String fileId,
        String status,
        String coverUrl,
        Double duration,
        String errorMsg,
        String eventType
) {
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String EVENT_PROCEDURE = "PROCEDURE";
    public static final String EVENT_DOCUMENT_SPLIT = "DOCUMENT_SPLIT";

    public static CallbackPayload processed(String fileId, String coverUrl, double duration) {
        return new CallbackPayload(fileId, STATUS_PROCESSED, coverUrl, duration, null, EVENT_PROCEDURE);
    }

    public static CallbackPayload failed(String fileId, String errorMsg) {
        return new CallbackPayload(fileId, STATUS_FAILED, null, null, errorMsg, EVENT_PROCEDURE);
    }

    public static CallbackPayload documentProcessed(String fileId) {
        return new CallbackPayload(fileId, STATUS_PROCESSED, null, null, null, EVENT_DOCUMENT_SPLIT);
    }

    public static CallbackPayload documentFailed(String fileId, String errorMsg) {
        return new CallbackPayload(fileId, STATUS_FAILED, null, null, errorMsg, EVENT_DOCUMENT_SPLIT);
    }
}
