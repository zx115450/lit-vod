package com.example.vod.gateway;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsPlaylistMediaUrisTest {

    @Test
    void parseShouldCollectMediaLinesOnly() {
        String playlist = """
                #EXTM3U
                #EXTINF:4.0,
                segment_000.ts
                #EXTINF:4.0,
                360p/segment_001.ts?e=1&sign=x
                #EXT-X-ENDLIST
                """;
        Set<String> uris = HlsPlaylistMediaUris.parse(playlist);
        assertEquals(2, uris.size());
        assertTrue(uris.contains("segment_000.ts"));
        assertTrue(uris.contains("360p/segment_001.ts"));
    }

    @Test
    void containsShouldMatchRelativePath() {
        Set<String> uris = Set.of("segment_000.ts", "360p/segment_001.ts");
        assertTrue(HlsPlaylistMediaUris.contains(uris, "segment_000.ts"));
        assertTrue(HlsPlaylistMediaUris.contains(uris, "360p/segment_001.ts?foo=1"));
        assertFalse(HlsPlaylistMediaUris.contains(uris, "segment_050.ts"));
    }
}
