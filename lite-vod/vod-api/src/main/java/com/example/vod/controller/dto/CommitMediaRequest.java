package com.example.vod.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 确认上传请求。
 */
public record CommitMediaRequest(
        @NotBlank String fileId,
        @NotBlank String filename
) {
}
