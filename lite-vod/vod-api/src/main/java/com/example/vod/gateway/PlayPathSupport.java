package com.example.vod.gateway;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放路径约定：请求 URI {@code /hls/{fileId}/...} ↔ MinIO key {@code hls/{fileId}/...}。
 *
 * <p>签发绑定的是该媒资实际清单：{@code media_url} 以 {@code master.m3u8} 结尾则绑 master，
 * 否则绑 {@code index.m3u8}。验签重算同一路径，请求 URI 必须落在同一 {@code fileId} 目录下。
 */
public final class PlayPathSupport {

    private static final Pattern HLS_URI = Pattern.compile("^/hls/([a-fA-F0-9]{8,64})/(.+)$");

    private PlayPathSupport() {
    }

    public record HlsRequest(String fileId, String relativePath, String requestPath, String objectKey) {
    }

    /**
     * 该 fileId 下被签名的 playlist 路径，由转码写回的 {@code media_url} 决定。
     *
     * <p>{@code hls/{fileId}/master.m3u8} → master；空、单档或其他值 → {@code index.m3u8}。
     * 子档与切片共用这一条签名 path。
     */
    public static String signedPlaylistPath(String fileId, String mediaUrl) {
        String playlist = mediaUrl != null && mediaUrl.endsWith("master.m3u8")
                ? "master.m3u8"
                : "index.m3u8";
        return "/hls/" + fileId + "/" + playlist;
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
        String objectKey = "hls/" + fileId + "/" + relative;
        return Optional.of(new HlsRequest(fileId, relative, requestPath, objectKey));
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
