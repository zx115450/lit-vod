package com.example.vod.gateway;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放路径约定：请求 URI {@code /hls/{fileId}/...} ↔ MinIO key {@code hls/{fileId}/...}。
 *
 * <p>第 10 步签发绑定的是完整 playlist 路径 {@code /hls/{fileId}/index.m3u8}；
 * 本步验签时用该路径重算签名，同时要求请求 URI 落在同一 {@code fileId} 目录下。
 */
public final class PlayPathSupport {

    private static final Pattern HLS_URI = Pattern.compile("^/hls/([a-fA-F0-9]{8,64})/(.+)$");

    private PlayPathSupport() {
    }

    public record HlsRequest(String fileId, String relativePath, String requestPath, String signedPath, String objectKey) {
    }

    public static Optional<HlsRequest> parse(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return Optional.empty();
        }
        String path = stripQuery(requestUri);
        if (path.contains("..")) {
            return Optional.empty();
        }
        Matcher m = HLS_URI.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        String fileId = m.group(1);
        String relative = m.group(2);
        if (relative.isBlank() || relative.contains("..")) {
            return Optional.empty();
        }
        String requestPath = "/hls/" + fileId + "/" + relative;
        String signedPath = "/hls/" + fileId + "/index.m3u8";
        String objectKey = "hls/" + fileId + "/" + relative;
        return Optional.of(new HlsRequest(fileId, relative, requestPath, signedPath, objectKey));
    }

    public static String contentType(String relativePath) {
        String lower = relativePath.toLowerCase();
        if (lower.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (lower.endsWith(".ts")) {
            return "video/MP2T";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    public static boolean isPlaylist(String relativePath) {
        return relativePath.toLowerCase().endsWith(".m3u8");
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
