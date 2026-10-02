package com.example.vod.controller.dto;

import java.util.List;

/**
 * DOCUMENT 章目录响应。
 */
public record ChaptersResponse(
        String sourceFileId,
        List<ChapterDto> chapters
) {
}
