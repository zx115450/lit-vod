package com.example.vod.gateway;

/**
 * 把 m3u8 中的相对媒体 URI 补上签名 query，使后续 .ts 请求携带同一套 e/exper/sign。
 *
 * <p>标准 HLS 相对路径不会自动继承 playlist 的 query，必须改写。
 */


/*
#EXTM3U
#EXT-X-VERSION:3
#EXTINF:4.000,
segment_000.ts
#EXTINF:4.000,
segment_001.ts
->
#EXTM3U
#EXT-X-VERSION:3
#EXTINF:4.000,
segment_000.ts?e=1&exper=0&sign=abc
#EXTINF:4.000,
segment_001.ts?e=1&exper=0&sign=abc
 */
public final class HlsPlaylistRewriter {

    private HlsPlaylistRewriter() {
    }

    /**
     * @param playlist 原始 m3u8 文本
     * @param query    不含前导 {@code ?}，例如 {@code e=1&exper=0&sign=abc}
     */
    public static String appendQueryToMediaUris(String playlist, String query) {
        if (playlist == null || playlist.isEmpty() || query == null || query.isBlank()) {
            return playlist;
        }
        String[] lines = playlist.split("\n", -1);
        StringBuilder out = new StringBuilder(playlist.length() + query.length() * 8);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(rewriteLine(lines[i], query));
        }
        return out.toString();
    }

    private static String rewriteLine(String line, String query) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            // 简单处理 EXT-X-MAP / EXT-X-KEY 里的 URI="..."
            return rewriteTaggedUri(line, query);
        }
        return appendQuery(trimmed, query);
    }

    private static String rewriteTaggedUri(String line, String query) {
        int idx = line.indexOf("URI=\"");
        if (idx < 0) {
            return line;
        }
        int start = idx + 5;
        int end = line.indexOf('"', start);
        if (end < 0) {
            return line;
        }
        String uri = line.substring(start, end);
        if (uri.startsWith("#") || uri.isBlank()) {
            return line;
        }
        String rewritten = appendQuery(uri, query);
        return line.substring(0, start) + rewritten + line.substring(end);
    }

    private static String appendQuery(String uri, String query) {
        if (uri.contains("e=") && uri.contains("sign=")) {
            return uri;
        }
        int hash = uri.indexOf('#');
        String beforeHash = hash >= 0 ? uri.substring(0, hash) : uri;
        String afterHash = hash >= 0 ? uri.substring(hash) : "";
        if (beforeHash.contains("?")) {
            return beforeHash + "&" + query + afterHash;
        }
        return beforeHash + "?" + query + afterHash;
    }
}
