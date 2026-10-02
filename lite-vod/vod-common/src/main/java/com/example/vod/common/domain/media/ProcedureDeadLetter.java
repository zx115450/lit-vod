package com.example.vod.common.domain.media;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 转码死信行，对应 procedure_dead_letter。
 * 与队列 vod.procedure.dlq 成对：表是可查询的业务记录，队列留着原始消息供重放。
 */
@Data
@NoArgsConstructor
public class ProcedureDeadLetter {

    private Long id;
    private Long taskId;
    private Long mediaId;
    private String fileId;
    private String objectKey;
    private String taskType;
    private Integer progressive;
    private Integer previewSeconds;
    private Integer attempt;
    private String errorMsg;
    private LocalDateTime createdAt;
}
