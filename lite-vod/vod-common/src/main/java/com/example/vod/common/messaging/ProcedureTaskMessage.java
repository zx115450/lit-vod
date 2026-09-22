package com.example.vod.common.messaging;

/**
 * 投递到转码队列的任务消息体。
 *
 * <p>支持二期渐进式多档：消息体自带 {@code taskType} 与 {@code progressive}，
 * Worker 按 {@code FAST} / {@code LADDER} / {@code FULL} 分发执行。
 * 旧消息只有前四个字段时，{@code taskType} 默认为 {@link TaskType#FULL}，
 * 行为与一期一次出齐完全一致，保证兼容。
 *
 * @param fileId      媒资对外标识
 * @param mediaId     media 表主键
 * @param objectKey   原始视频在 MinIO 中的对象键
 * @param taskId      media_task 表主键，Worker 幂等/回写用
 * @param taskType    任务类型；null 时按 FULL 处理
 * @param progressive 是否启用渐进式；null 时按 false 处理
 */
public record ProcedureTaskMessage(
        String fileId,
        Long mediaId,
        String objectKey,
        Long taskId,
        TaskType taskType,
        Boolean progressive
) {

    /**
     * 兼容一期：未携带 taskType / progressive 的消息按「一次出齐」处理。
     */
    public ProcedureTaskMessage(String fileId, Long mediaId, String objectKey, Long taskId) {
        this(fileId, mediaId, objectKey, taskId, TaskType.FULL, false);
    }

    public TaskType taskType() {
        return taskType == null ? TaskType.FULL : taskType;
    }

    public boolean isProgressive() {
        return progressive != null && progressive;
    }

    public enum TaskType {
        FAST,
        LADDER,
        FULL
    }
}
