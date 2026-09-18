package com.example.vod.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsPlaylistRewriterTest {

    @Test
    void appendQueryToRelativeSegments() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXTINF:4.000,
                segment_000.ts
                #EXTINF:4.000,
                segment_001.ts
                """;
        String out = HlsPlaylistRewriter.appendQueryToMediaUris(playlist, "e=1&exper=0&sign=abc");

        assertTrue(out.contains("segment_000.ts?e=1&exper=0&sign=abc"));
        assertTrue(out.contains("segment_001.ts?e=1&exper=0&sign=abc"));
        assertTrue(out.contains("#EXTM3U"));
    }

    @Test
    void appendQueryShouldUseAmpersandWhenUriAlreadyHasQuery() {
        String out = HlsPlaylistRewriter.appendQueryToMediaUris("clip.ts?x=1\n", "e=1&sign=a");
        assertTrue(out.startsWith("clip.ts?x=1&e=1&sign=a"));
    }

    @Test
    void appendQueryShouldRewriteExtMapUri() {
        String line = "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"1000@0\"";
        String out = HlsPlaylistRewriter.appendQueryToMediaUris(line, "e=9&sign=z");
        assertEquals("#EXT-X-MAP:URI=\"init.mp4?e=9&sign=z\",BYTERANGE=\"1000@0\"", out);
    }

    @Test
    void appendQueryShouldBeIdempotentWhenAlreadySigned() {
        String line = "segment_000.ts?e=1&exper=0&sign=abc";
        assertEquals(line, HlsPlaylistRewriter.appendQueryToMediaUris(line, "e=1&exper=0&sign=abc"));
    }
}
