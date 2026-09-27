package com.example.vod.service;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.config.PlaySignProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayAuthServiceTest {

    private static final String FILE_ID = "f7c2a1b0e9d84f6a";
    private static final String SIGNED_PATH = "/hls/" + FILE_ID + "/index.m3u8";
    private static final String PREVIEW_PATH = "/hls/" + FILE_ID + "/preview.m3u8";

    private PlaySignService playSignService;
    private MinioStorage minioStorage;
    private MediaMapper mediaMapper;
    private PlayAuthService auth;
    private PlayAuthService authL2;

    @BeforeEach
    void setUp() {
        playSignService = new PlaySignService(new PlaySignProperties("test-secret", "http://localhost:8080", 3600L));
        minioStorage = mock(MinioStorage.class);
        mediaMapper = mock(MediaMapper.class);
        auth = new PlayAuthService(playSignService, mediaMapper, minioStorage,
                new PreviewProperties(false, 120, 1800));
        authL2 = new PlayAuthService(playSignService, mediaMapper, minioStorage,
                new PreviewProperties(true, 120, 1800));
    }

    @Test
    void shouldAcceptValidOriginalUri() {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        String uri = SIGNED_PATH + "?e=" + e + "&exper=0&sign=" + sign;

        assertTrue(auth.authorizeOriginalUri(uri));
    }

    @Test
    void shouldRejectMissingSign() {
        assertFalse(auth.authorizeOriginalUri(SIGNED_PATH));
        assertFalse(auth.authorizeOriginalUri(SIGNED_PATH + "?e=1"));
        assertFalse(auth.authorizeOriginalUri(null));
        assertFalse(auth.authorizeOriginalUri(""));
    }

    @Test
    void shouldRejectExpired() {
        long past = playSignService.nowEpoch() - 10;
        String sign = playSignService.sign(SIGNED_PATH, past, 0);
        String uri = SIGNED_PATH + "?e=" + past + "&exper=0&sign=" + sign;

        assertFalse(auth.authorizeOriginalUri(uri));
    }

    @Test
    void shouldRejectTamperedPath() {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        String uri = "/hls/aaaaaaaaaaaaaaaa/index.m3u8?e=" + e + "&exper=0&sign=" + sign;

        assertFalse(auth.authorizeOriginalUri(uri));
    }

    @Test
    void shouldAllowTsUnderPlaylistSignature() {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        String uri = "/hls/" + FILE_ID + "/segment_000.ts?e=" + e + "&exper=0&sign=" + sign;

        assertTrue(auth.authorizeOriginalUri(uri));
    }

    @Test
    void l2ShouldRejectUnlistedTsWithPreviewSign() {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);
        String preview = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n#EXT-X-ENDLIST\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/preview.m3u8")))
                .thenReturn(new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8)));

        String uri = "/hls/" + FILE_ID + "/segment_050.ts?e=" + e + "&exper=120&sign=" + sign;
        assertFalse(authL2.authorizeOriginalUri(uri));
    }

    @Test
    void l2ShouldAllowListedTsWithPreviewSign() {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);
        String preview = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n#EXT-X-ENDLIST\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/preview.m3u8")))
                .thenReturn(new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8)));

        String uri = "/hls/" + FILE_ID + "/segment_000.ts?e=" + e + "&exper=120&sign=" + sign;
        assertTrue(authL2.authorizeOriginalUri(uri));
    }

    @Test
    void parseQueryShouldTakeFirstValue() {
        Map<String, String> q = PlayAuthService.parseQuery("/hls/x?e=1&sign=abc&e=2");
        assertEquals("1", q.get("e"));
        assertEquals("abc", q.get("sign"));
    }

    private static Media mediaWithUrl(String mediaUrl) {
        Media media = new Media();
        media.setFileId(FILE_ID);
        media.setMediaUrl(mediaUrl);
        return media;
    }
}
