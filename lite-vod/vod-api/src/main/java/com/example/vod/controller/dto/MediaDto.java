package com.example.vod.controller.dto;

import com.example.vod.common.domain.media.MediaStatus;

import java.time.LocalDateTime;

/**
 * 媒资对外 DTO。
 */
public record MediaDto(
        Long id,
        String fileId,
        String objectKey,
        String filename,
        String mediaUrl,
        String coverUrl,
        Float duration,
        Long size,
        MediaStatus status,
        String statusText,
        String errorMsg,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
