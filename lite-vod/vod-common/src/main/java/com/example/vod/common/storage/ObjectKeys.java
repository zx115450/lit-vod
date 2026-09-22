package com.example.vod.common.storage;

/**
 * MinIO 对象键约定，避免各处手拼路径。
 *
 * <p>首期单码率：
 * <pre>
 * bucket: vod
 *   raw/{fileId}/source.mp4
 *   hls/{fileId}/index.m3u8
 *   hls/{fileId}/segment_000.ts
 *   cover/{fileId}.jpg
 * </pre>
 *
 * <p>二期 ABR 多码率：
 * <pre>
 *   hls/{fileId}/master.m3u8
 *   hls/{fileId}/360p/index.m3u8
 *   hls/{fileId}/360p/segment_000.ts
 *   hls/{fileId}/480p/...
 * </pre>
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    public static String raw(String fileId) {
        return "raw/" + fileId + "/source.mp4";
    }

    /** 单码率首期：根目录 index.m3u8。 */
    public static String hlsPlaylist(String fileId) {
        return "hls/" + fileId + "/index.m3u8";
    }

    /** 单码率首期：根目录 segment_xxx.ts。 */
    public static String hlsSegment(String fileId, int index) {
        return String.format("hls/%s/segment_%03d.ts", fileId, index);
    }

    /** 二期 ABR：master.m3u8。 */
    public static String hlsMaster(String fileId) {
        return "hls/" + fileId + "/master.m3u8";
    }

    /** 二期 ABR：某档子清单。 */
    public static String hlsVariantPlaylist(String fileId, String label) {
        return String.format("hls/%s/%s/index.m3u8", fileId, label);
    }

    /** 二期 ABR：某档切片。 */
    public static String hlsVariantSegment(String fileId, String label, int index) {
        return String.format("hls/%s/%s/segment_%03d.ts", fileId, label, index);
    }

    /** 二期 ABR：某档目录前缀。 */
    public static String hlsVariantPrefix(String fileId, String label) {
        return String.format("hls/%s/%s/", fileId, label);
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
