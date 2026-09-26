package com.example.vod.worker.ffmpeg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPlaylistBuilderTest {

    private static final String SOURCE = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:4.0,
            segment_000.ts
            #EXTINF:4.0,
            segment_001.ts
            #EXTINF:4.0,
            segment_002.ts
            #EXTINF:2.0,
            segment_003.ts
            #EXT-X-ENDLIST
            """;

    @Test
    void shouldKeepWholeSegmentsWhenCrossingBoundary() {
        // 120s limit but only ~14s media → all segments
        String out = PreviewPlaylistBuilder.build(SOURCE, 120);
        assertTrue(out.contains("segment_000.ts"));
        assertTrue(out.contains("segment_003.ts"));
        assertTrue(out.trim().endsWith("#EXT-X-ENDLIST"));
    }

    @Test
    void shouldRoundUpToCompleteSegment() {
        // 5s → first segment 4s + second segment (round up) → two segments
        String out = PreviewPlaylistBuilder.build(SOURCE, 5);
        assertTrue(out.contains("segment_000.ts"));
        assertTrue(out.contains("segment_001.ts"));
        assertFalse(out.contains("segment_002.ts"));
    }

    @Test
    void shouldStopExactlyOnBoundary() {
        String out = PreviewPlaylistBuilder.build(SOURCE, 8);
        assertTrue(out.contains("segment_000.ts"));
        assertTrue(out.contains("segment_001.ts"));
        assertFalse(out.contains("segment_002.ts"));
    }

    @Test
    void shouldPrefixVariantUri() {
        String out = PreviewPlaylistBuilder.build(SOURCE, 4, "360p");
        assertTrue(out.contains("360p/segment_000.ts"));
        assertFalse(out.contains("\nsegment_000.ts"));
    }

    @Test
    void shouldRejectBlankPlaylist() {
        assertThrows(IllegalArgumentException.class, () -> PreviewPlaylistBuilder.build("  ", 10));
    }

    @Test
    void shouldRejectPlaylistWithoutSegments() {
        String empty = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-ENDLIST\n";
        assertThrows(IllegalArgumentException.class, () -> PreviewPlaylistBuilder.build(empty, 10));
    }
}
