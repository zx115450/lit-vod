package com.example.vod.common.domain.media;

/**
 * 媒资生命周期状态。
 *
 * <ul>
 *   <li>UPLOADING(0) - 上传中</li>
 *   <li>UPLOADED(1) - 已上传</li>
 *   <li>PROCESSING(2) - 处理中</li>
 *   <li>FINISHED(3) - 已完成</li>
 *   <li>FAILED(4) - 失败</li>
 * </ul>
 */
public enum MediaStatus {
    UPLOADING(0, "上传中"),
    UPLOADED(1, "已上传"),
    PROCESSING(2, "处理中"),
    FINISHED(3, "已完成"),
    FAILED(4, "失败");

    private final int code;
    private final String label;

    MediaStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static MediaStatus of(int code) {
        for (MediaStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown media status code: " + code);
    }
}
