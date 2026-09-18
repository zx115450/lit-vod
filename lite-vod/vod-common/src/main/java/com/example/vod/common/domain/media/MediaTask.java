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
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
