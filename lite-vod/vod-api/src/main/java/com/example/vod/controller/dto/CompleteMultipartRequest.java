package com.example.vod.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 完成分片合并请求。
 *
 * @param uploadId MinIO multipart 会话 ID
 * @param parts    各片的 (partNumber, etag) 列表，按 partNumber 升序提交
 */
public record CompleteMultipartRequest(
        @NotBlank String uploadId,
        @Valid List<PartEtag> parts
) {
}
