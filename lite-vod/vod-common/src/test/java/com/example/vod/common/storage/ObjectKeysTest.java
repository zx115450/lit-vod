package com.example.vod.common.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectKeysTest {

    @Test
    void shouldFollowLiteVodPathConvention() {
        String fileId = "f7c2a1b0e9d84f6a";

        assertEquals("raw/f7c2a1b0e9d84f6a/source.mp4", ObjectKeys.raw(fileId));
        assertEquals("hls/f7c2a1b0e9d84f6a/index.m3u8", ObjectKeys.hlsPlaylist(fileId));
        assertEquals("hls/f7c2a1b0e9d84f6a/segment_000.ts", ObjectKeys.hlsSegment(fileId, 0));
        assertEquals("hls/f7c2a1b0e9d84f6a/master.m3u8", ObjectKeys.hlsMaster(fileId));
        assertEquals("hls/f7c2a1b0e9d84f6a/preview.m3u8", ObjectKeys.hlsPreview(fileId));
        assertEquals("hls/f7c2a1b0e9d84f6a/360p/index.m3u8", ObjectKeys.hlsVariantPlaylist(fileId, "360p"));
        assertEquals("hls/f7c2a1b0e9d84f6a/480p/segment_007.ts", ObjectKeys.hlsVariantSegment(fileId, "480p", 7));
        assertEquals("hls/f7c2a1b0e9d84f6a/720p/", ObjectKeys.hlsVariantPrefix(fileId, "720p"));
        assertEquals("cover/f7c2a1b0e9d84f6a.jpg", ObjectKeys.cover(fileId));
        assertEquals("raw/f7c2a1b0e9d84f6a/", ObjectKeys.rawPrefix(fileId));
        assertEquals("hls/f7c2a1b0e9d84f6a/", ObjectKeys.hlsPrefix(fileId));
    }
}
