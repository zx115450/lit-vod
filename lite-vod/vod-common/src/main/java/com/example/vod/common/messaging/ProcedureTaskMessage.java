package com.example.vod.common.messaging;

/**
 * 投递到转码队列的任务消息体。
 *
 * @param fileId    媒资对外标识
 * @param mediaId   media 表主键
 * @param objectKey 原始视频在 MinIO 中的对象键
 * @param taskId    media_task 表主键，Worker 幂等/回写用
 */
public record ProcedureTaskMessage(
        String fileId,
        Long mediaId,
        String objectKey,
        Long taskId
) {
}
