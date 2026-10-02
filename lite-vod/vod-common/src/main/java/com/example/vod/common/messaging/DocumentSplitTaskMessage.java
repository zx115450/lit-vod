package com.example.vod.common.messaging;

import com.example.vod.common.domain.media.SplitRule;

/**
 * 投递到 {@code vod.document.split} 的切章任务消息体。
 *
 * <p>与 {@link ProcedureTaskMessage} 隔离：DOCUMENT 不得进入 FFmpeg 转码队列。
 *
 * @param fileId    DOCUMENT 的 fileId
 * @param mediaId   media 表主键
 * @param objectKey 原件对象键
 * @param taskId    media_task 主键
 * @param splitRule 切章规则；null 时 Worker 按 {@link SplitRule#MARKDOWN}
 */
public record DocumentSplitTaskMessage(
        String fileId,
        Long mediaId,
        String objectKey,
        Long taskId,
        SplitRule splitRule
) {

    public SplitRule splitRuleOrDefault() {
        return splitRule == null ? SplitRule.MARKDOWN : splitRule;
    }
}
