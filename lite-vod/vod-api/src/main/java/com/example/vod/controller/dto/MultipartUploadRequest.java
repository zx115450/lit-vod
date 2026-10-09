package com.example.vod.controller.dto;

import jakarta.validation.constraints.Positive;

/**
 * 申请 multipart 上传凭证请求。
 *
 * <ul>
 *   <li>{@code filename}      - 原始文件名，仅记录用</li>
 *   <li>{@code contentType}   - 对象 Content-Type，如 video/mp4；为空时按 assetType 默认</li>
 *   <li>{@code contentLength} - 文件总字节数，用于计算 partCount</li>
 *   <li>{@code partSize}      - 每片字节数，建议 5MiB～64MiB；S3 单片下限 5MiB（最后一片除外）</li>
 *   <li>{@code assetType}     - 可选；缺省 VIDEO；DOCUMENT 供电子书大文件分片</li>
 * </ul>
 */
public record MultipartUploadRequest(
        String filename,
        String contentType,
        @Positive long contentLength,
        @Positive long partSize,
        String assetType
) {
    /** 兼容旧调用（默认 VIDEO）。 */
    public MultipartUploadRequest(String filename, String contentType, long contentLength, long partSize) {
        this(filename, contentType, contentLength, partSize, null);
    }
}
