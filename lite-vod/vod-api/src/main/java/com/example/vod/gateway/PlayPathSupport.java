package com.example.vod.gateway;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放路径约定：请求 URI {@code /hls/{fileId}/...} ↔ MinIO key {@code hls/{fileId}/...}。
 *
 * <p>正片签发绑定该媒资实际清单：{@code media_url} 以 {@code master.m3u8} 结尾则绑 master，
 * 否则绑 {@code index.m3u8}。试看 L2 签发绑定 {@code preview.m3u8}。
 * 验签按请求所属清单类型选择 signedPath，试看 sign 不得用于正片 path。
 */
public final class PlayPathSupport {

    private static final Pattern HLS_URI = Pattern.compile("^/hls/([a-fA-F0-9]{8,64})/(.+)$");
    private static final String PREVIEW_PLAYLIST = "preview.m3u8";

    private PlayPathSupport() {
    }

    public record HlsRequest(String fileId, String relativePath, String requestPath, String objectKey) {
    }

    /**
     * 正片清单签名 path，由转码写回的 {@code media_url} 决定。
     *
     * <p>{@code hls/{fileId}/master.m3u8} → master；空、单档或其他值 → {@code index.m3u8}。
     */
    public static String signedPlaylistPath(String fileId, String mediaUrl) {
        String playlist = mediaUrl != null && mediaUrl.endsWith("master.m3u8")
                ? "master.m3u8"
                : "index.m3u8";
        return "/hls/" + fileId + "/" + playlist;
    }

    /** 试看 L2 清单签名 path。 */
    public static String previewPlaylistPath(String fileId) {
        return "/hls/" + fileId + "/" + PREVIEW_PLAYLIST;
    }

    public static boolean isPreviewPlaylist(String relativePath) {
        return relativePath != null && relativePath.equalsIgnoreCase(PREVIEW_PLAYLIST);
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
