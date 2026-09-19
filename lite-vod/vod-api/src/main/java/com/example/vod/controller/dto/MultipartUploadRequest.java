package com.example.vod.controller.dto;

import jakarta.validation.constraints.Positive;

/**
 * 申请 multipart 上传凭证请求。
 *
 * <ul>
 *   <li>{@code filename}      - 原始文件名，仅记录用</li>
 *   <li>{@code contentType}   - 对象 Content-Type，如 video/mp4；为空时默认 video/mp4</li>
 *   <li>{@code contentLength} - 文件总字节数，用于计算 partCount</li>
 *   <li>{@code partSize}      - 每片字节数，建议 5MiB～64MiB；S3 单片下限 5MiB（最后一片除外）</li>
 * </ul>
 */
public record MultipartUploadRequest(
        String filename,
        String contentType,
        @Positive long contentLength,
        @Positive long partSize
) {
}
