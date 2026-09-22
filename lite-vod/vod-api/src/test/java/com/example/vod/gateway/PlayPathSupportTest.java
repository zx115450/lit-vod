package com.example.vod.gateway;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayPathSupportTest {

    @Test
    void parseShouldExtractFileIdAndObjectKey() {
        Optional<PlayPathSupport.HlsRequest> parsed =
                PlayPathSupport.parse("/hls/f7c2a1b0e9d84f6a/segment_000.ts");

        assertTrue(parsed.isPresent());
        PlayPathSupport.HlsRequest hls = parsed.get();
        assertEquals("f7c2a1b0e9d84f6a", hls.fileId());
        assertEquals("segment_000.ts", hls.relativePath());
        assertEquals("hls/f7c2a1b0e9d84f6a/segment_000.ts", hls.objectKey());
    }

    @Test
    void parseShouldRejectTraversal() {
        assertTrue(PlayPathSupport.parse("/hls/f7c2a1b0e9d84f6a/../evil.ts").isEmpty());
        assertTrue(PlayPathSupport.parse("/hls/../index.m3u8").isEmpty());
    }

    @Test
    void parseShouldRejectNonHexFileId() {
        assertTrue(PlayPathSupport.parse("/hls/not-hex!/index.m3u8").isEmpty());
    }

    @Test
    void contentTypeShouldMatchExtension() {
        assertEquals("application/vnd.apple.mpegurl", PlayPathSupport.contentType("index.m3u8"));
        assertEquals("application/vnd.apple.mpegurl", PlayPathSupport.contentType("master.m3u8"));
        assertEquals("video/MP2T", PlayPathSupport.contentType("segment_001.ts"));
        assertTrue(PlayPathSupport.isPlaylist("index.m3u8"));
        assertTrue(PlayPathSupport.isPlaylist("master.m3u8"));
        assertFalse(PlayPathSupport.isPlaylist("segment_001.ts"));
    }

    @Test
    void signedPlaylistPathShouldFollowMediaUrl() {
        String fileId = "f7c2a1b0e9d84f6a";
        assertEquals("/hls/" + fileId + "/index.m3u8",
                PlayPathSupport.signedPlaylistPath(fileId, "hls/" + fileId + "/index.m3u8"));
        assertEquals("/hls/" + fileId + "/index.m3u8",
                PlayPathSupport.signedPlaylistPath(fileId, null));
        assertEquals("/hls/" + fileId + "/master.m3u8",
                PlayPathSupport.signedPlaylistPath(fileId, "hls/" + fileId + "/master.m3u8"));
    }
}
