package com.example.vod.controller.dto;

import com.example.vod.common.domain.media.AssetType;

/**
 * 章目录条目（{@code GET /vod/medias/{sourceFileId}/chapters}）。
 */
public record ChapterDto(
        int chapterNo,
        String title,
        String fileId,
        int wordCount,
        AssetType assetType
) {
}
