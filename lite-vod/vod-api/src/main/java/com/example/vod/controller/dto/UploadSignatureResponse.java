package com.example.vod.controller.dto;

/**
 * 申请上传凭证响应。
 */
public record UploadSignatureResponse(
        String fileId,
        String uploadUrl,
        String objectKey,
        long expireAt
) {
}
