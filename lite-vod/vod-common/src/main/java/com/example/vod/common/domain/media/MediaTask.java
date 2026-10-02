package com.example.vod.common.domain.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 媒资处理任务，对应 media_task 表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaTask {

    private Long id;
    private Long mediaId;
    private String fileId;
    private MediaTaskType type;
    private MediaTaskStatus status;
    private Integer attempt;
    /** 任务扩展 JSON，如 {@code {"splitRule":"MARKDOWN"}}，供 L2/L3 Worker 读取。 */
    private String payload;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
