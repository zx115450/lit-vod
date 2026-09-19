package com.example.vod.controller.dto;

/**
 * 单个分片的预签名上传 URL。
 *
 * @param partNumber 分片编号，从 1 开始
 * @param uploadUrl  预签名 UploadPart URL，带 uploadId + partNumber
 */
public record PartUrl(
        int partNumber,
        String uploadUrl
) {
}
