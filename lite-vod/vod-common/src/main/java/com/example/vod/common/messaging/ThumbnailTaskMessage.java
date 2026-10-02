package com.example.vod.common.messaging;

/**
 * 投递到 {@code vod.image.thumbnail} 的缩略图任务消息体。
 *
 * <p>与转码队列隔离：IMAGE 不得进入 FFmpeg。
 *
 * @param fileId    IMAGE 的 fileId
 * @param mediaId   media 表主键
 * @param objectKey 原图对象键；缩略图失败时保持此键
 * @param taskId    media_task 主键
 */
public record ThumbnailTaskMessage(
        String fileId,
        Long mediaId,
        String objectKey,
        Long taskId
) {
}
