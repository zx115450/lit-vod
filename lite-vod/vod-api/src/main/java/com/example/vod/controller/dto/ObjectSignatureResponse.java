package com.example.vod.controller.dto;

import com.example.vod.common.domain.media.AssetType;

/**
 * 对象预签名 GET 响应：调用方直打 {@code objectUrl}，字节不过 vod-api。
 */
public record ObjectSignatureResponse(
        String fileId,
        String objectUrl,
        String objectKey,
        AssetType assetType,
        long expireAt
) {
}
