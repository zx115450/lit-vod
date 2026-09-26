package com.example.vod.gateway;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 从 media playlist 提取相对媒体 URI（去 query），供试看 L2 校验「是否在 preview 清单内」。
 */
public final class HlsPlaylistMediaUris {

    private HlsPlaylistMediaUris() {
    }

    public static Set<String> parse(String playlist) {
        if (playlist == null || playlist.isBlank()) {
            return Set.of();
        }
        Set<String> uris = new HashSet<>();
        for (String raw : playlist.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            uris.add(stripQuery(line));
        }
        return Collections.unmodifiableSet(uris);
    }

    public static boolean contains(Set<String> mediaUris, String relativePath) {
        if (mediaUris == null || mediaUris.isEmpty() || relativePath == null) {
            return false;
        }
        String path = stripQuery(relativePath.trim());
        if (mediaUris.contains(path)) {
            return true;
        }
        // 大小写不敏感兜底（Windows 调试）
        String lower = path.toLowerCase(Locale.ROOT);
        for (String u : mediaUris) {
            if (u.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q >= 0 ? uri.substring(0, q) : uri;
    }
}
