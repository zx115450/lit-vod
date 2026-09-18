package com.example.vod.common.storage;

/**
 * MinIO 对象键约定，避免各处手拼路径。
 *
 * <pre>
 * bucket: vod
 *   raw/{fileId}/source.mp4
 *   hls/{fileId}/index.m3u8
 *   hls/{fileId}/segment_000.ts
 *   cover/{fileId}.jpg
 * </pre>
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    public static String raw(String fileId) {
        return "raw/" + fileId + "/source.mp4";
    }

    public static String hlsPlaylist(String fileId) {
        return "hls/" + fileId + "/index.m3u8";
    }

    public static String hlsSegment(String fileId, int index) {
        return String.format("hls/%s/segment_%03d.ts", fileId, index);
    }

    public static String cover(String fileId) {
        return "cover/" + fileId + ".jpg";
    }

    public static String rawPrefix(String fileId) {
        return "raw/" + fileId + "/";
    }

    public static String hlsPrefix(String fileId) {
        return "hls/" + fileId + "/";
    }
}
