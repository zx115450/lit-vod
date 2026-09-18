package com.example.vod.controller.dto;

/**
 * 播放签名签发响应，对应步骤 10。
 *
 * @param fileId    媒资 ID
 * @param playUrl   带签名的可播放 URL，指向第 11 步的 Nginx 网关
 * @param signature 与 URL 中 sign 参数相同，便于调用方单独携带
 * @param expireAt  签名过期时间戳（秒）
 */
public record PlaySignatureResponse(
        String fileId,
        String playUrl,
        String signature,
        long expireAt
) {
}
