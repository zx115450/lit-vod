package com.example.vod.controller.dto;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.MediaStatus;

import java.time.LocalDateTime;

/**
 * 媒资对外 DTO。
 */
public record MediaDto(
        Long id,
        String fileId,
        AssetType assetType,
        String objectKey,
        String filename,
        String mimeType,
        String parentFileId,
        Integer chapterNo,
        Integer pageCount,
        String mediaUrl,
        String coverUrl,
        Float duration,
        Long size,
        MediaStatus status,
        String statusText,
        Integer previewSeconds,
        String errorMsg,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
