package com.example.vod.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 中止分片上传请求。
 *
 * @param uploadId MinIO multipart 会话 ID
 */
public record AbortMultipartRequest(
        @NotBlank String uploadId
) {
}
