package com.example.vod.common.domain.media;

/**
 * 渐进式多档补档状态。
 *
 * <ul>
 *   <li>PENDING(0) - 待补档（快档刚完成）</li>
 *   <li>RUNNING(1) - 补档进行中</li>
 *   <li>READY(2) - 全部计划档位齐全</li>
 *   <li>PARTIAL_FAILED(3) - 部分档位失败，但媒资仍可播</li>
 * </ul>
 */
public enum LadderStatus {
    PENDING(0, "待补档"),
    RUNNING(1, "补档中"),
    READY(2, "档位齐全"),
    PARTIAL_FAILED(3, "部分档位失败");

    private final int code;
    private final String label;

    LadderStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static LadderStatus of(int code) {
        for (LadderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown ladder status code: " + code);
    }
}
