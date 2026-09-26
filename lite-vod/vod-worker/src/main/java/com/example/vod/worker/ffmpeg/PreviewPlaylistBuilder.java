package com.example.vod.worker.ffmpeg;

import com.example.vod.common.config.PreviewProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从正片 media playlist 截取前 {@code previewSeconds} 秒，生成试看 {@code preview.m3u8}。
 *
 * <p>累加 {@code #EXTINF}，向上取整到完整 ts（最多试看到第 K 片结束）。
 * 可选 {@code uriPrefix}：ABR 时把相对 URI 写成 {@code 360p/segment_000.ts}。
 */
public final class PreviewPlaylistBuilder {

    private static final Pattern EXTINF = Pattern.compile(
            "^#EXTINF:([0-9]+(?:\\.[0-9]+)?)\\s*,.*$", Pattern.CASE_INSENSITIVE);

    private PreviewPlaylistBuilder() {
    }

    /**
     * @param sourcePlaylist 源 media playlist 文本（单档或某档 index.m3u8）
     * @param previewSeconds 试看秒数，&lt;=0 时按 {@link PreviewProperties#DEFAULT_SECONDS}
     * @param uriPrefix      非空时加在媒体 URI 前，如 {@code 360p/}；已带尾斜杠或自动补
     */
    public static String build(String sourcePlaylist, int previewSeconds, String uriPrefix) {
        if (sourcePlaylist == null || sourcePlaylist.isBlank()) {
            throw new IllegalArgumentException("source playlist is blank");
        }
        int limit = previewSeconds > 0 ? previewSeconds : PreviewProperties.DEFAULT_SECONDS;
        String prefix = normalizePrefix(uriPrefix);

        String[] lines = sourcePlaylist.split("\n", -1);
        List<String> headers = new ArrayList<>();
        List<String> body = new ArrayList<>();
        double accumulated = 0;
        boolean collecting = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("#EXT-X-ENDLIST")) {
                continue;
            }
            if (!collecting) {
                continue;
            }

            Matcher inf = EXTINF.matcher(trimmed);
            if (inf.matches()) {
                double dur = Double.parseDouble(inf.group(1));
                String mediaUri = nextMediaUri(lines, i + 1);
                if (mediaUri == null) {
                    throw new IllegalArgumentException("EXTINF without media URI");
                }
                body.add(trimmed);
                body.add(prefix + stripQuery(mediaUri));
                accumulated += dur;
                if (accumulated >= limit) {
                    collecting = false;
                }
                continue;
            }

            if (trimmed.startsWith("#")) {
                if (isHeaderTag(trimmed)) {
                    headers.add(trimmed);
                }
                continue;
            }
            // 孤立媒体行（无前置 EXTINF）忽略；正常 HLS 不会出现
        }

        if (body.isEmpty()) {
            throw new IllegalArgumentException("no segments within preview window");
        }

        StringBuilder out = new StringBuilder();
        if (headers.isEmpty()) {
            out.append("#EXTM3U\n");
        } else {
            for (String h : headers) {
                out.append(h).append('\n');
            }
        }
        for (String b : body) {
            out.append(b).append('\n');
        }
        out.append("#EXT-X-ENDLIST");
        return out.toString();
    }

    public static String build(String sourcePlaylist, int previewSeconds) {
        return build(sourcePlaylist, previewSeconds, null);
    }

    private static String nextMediaUri(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                if (EXTINF.matcher(t).matches() || t.equalsIgnoreCase("#EXT-X-ENDLIST")) {
                    return null;
                }
                continue;
            }
            return t;
        }
        return null;
    }

    private static boolean isHeaderTag(String trimmed) {
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("#EXTM3U")
                || upper.startsWith("#EXT-X-VERSION")
                || upper.startsWith("#EXT-X-TARGETDURATION")
                || upper.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || upper.startsWith("#EXT-X-PLAYLIST-TYPE")
                || upper.startsWith("#EXT-X-INDEPENDENT-SEGMENTS");
    }

    private static String normalizePrefix(String uriPrefix) {
        if (uriPrefix == null || uriPrefix.isBlank()) {
            return "";
        }
        String p = uriPrefix.trim();
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.endsWith("/") ? p : p + "/";
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
