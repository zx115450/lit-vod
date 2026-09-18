package com.example.vod.common.domain.media;

/**
 * media_task 任务状态。
 *
 * <ul>
 *   <li>PENDING(0) - 待执行</li>
 *   <li>RUNNING(1) - 执行中</li>
 *   <li>SUCCESS(2) - 成功</li>
 *   <li>FAILED(3) - 失败</li>
 * </ul>
 */
public enum MediaTaskStatus {
    PENDING(0, "待执行"),
    RUNNING(1, "执行中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败");

    private final int code;
    private final String label;

    MediaTaskStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static MediaTaskStatus of(int code) {
        for (MediaTaskStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown media task status code: " + code);
    }

    /**
     * 是否处于未完结状态（PENDING / RUNNING）。
     */
    public boolean isIncomplete() {
        return this == PENDING || this == RUNNING;
    }
}
