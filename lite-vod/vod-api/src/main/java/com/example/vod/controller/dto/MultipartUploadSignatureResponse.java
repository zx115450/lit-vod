package com.example.vod.controller.dto;

import java.util.List;

/**
 * 申请 multipart 上传凭证响应。
 *
 * <ul>
 *   <li>{@code fileId}    - 媒资唯一标识，已落库 UPLOADING</li>
 *   <li>{@code objectKey} - 对象键，如 raw/{fileId}/source.mp4</li>
 *   <li>{@code uploadId}  - MinIO multipart 会话 ID，贯穿后续 complete / abort</li>
 *   <li>{@code partSize}  - 每片字节数</li>
 *   <li>{@code partCount} - 总片数</li>
 *   <li>{@code parts}     - 各片预签名 URL 列表（一次发齐模式）</li>
 *   <li>{@code expireAt}  - URL 过期时间（Unix 秒）</li>
 * </ul>
 */
public record MultipartUploadSignatureResponse(
        String fileId,
        String objectKey,
        String uploadId,
        long partSize,
        int partCount,
        List<PartUrl> parts,
        long expireAt
) {
}
