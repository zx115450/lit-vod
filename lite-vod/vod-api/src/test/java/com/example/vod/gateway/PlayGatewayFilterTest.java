package com.example.vod.gateway;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.config.PlaySignProperties;
import com.example.vod.service.PlaySignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayGatewayFilterTest {

    private static final String FILE_ID = "f7c2a1b0e9d84f6a";
    private static final String SIGNED_PATH = "/hls/" + FILE_ID + "/index.m3u8";
    private static final String PREVIEW_PATH = "/hls/" + FILE_ID + "/preview.m3u8";
    private static final long EXPIRE = 1710003600L;

    private PlaySignService playSignService;
    private MinioStorage minioStorage;
    private MediaMapper mediaMapper;
    private PlayGatewayFilter filter;
    private PlayGatewayFilter filterL2;

    @BeforeEach
    void setUp() {
        playSignService = new PlaySignService(new PlaySignProperties("test-secret", "http://localhost:8080", 3600L));
        minioStorage = mock(MinioStorage.class);
        mediaMapper = mock(MediaMapper.class);
        filter = new PlayGatewayFilter(playSignService, minioStorage, mediaMapper,
                new PreviewProperties(false, 120));
        filterL2 = new PlayGatewayFilter(playSignService, minioStorage, mediaMapper,
                new PreviewProperties(true, 120));
    }

    @Test
    void shouldReturn403WithoutSign() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", SIGNED_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void shouldReturn403WhenExpired() throws Exception {
        String sign = playSignService.sign(SIGNED_PATH, EXPIRE, 0);
        long past = playSignService.nowEpoch() - 10;
        String pastSign = playSignService.sign(SIGNED_PATH, past, 0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", SIGNED_PATH);
        request.setParameter("e", String.valueOf(past));
        request.setParameter("exper", "0");
        request.setParameter("sign", pastSign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertFalse(sign.isEmpty());
    }

    @Test
    void shouldStreamPlaylistAndRewriteSegments() throws Exception {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 300);
        String playlist = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/index.m3u8")))
                .thenReturn(new ByteArrayInputStream(playlist.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", SIGNED_PATH);
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "300");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("application/vnd.apple.mpegurl", response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("#EXTM3U"));
        assertTrue(body.contains("segment_000.ts?e=" + e + "&exper=300&sign=" + sign));
        assertEquals("*", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void shouldAllowTsUnderSameFileIdWithPlaylistSignature() throws Exception {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        byte[] ts = new byte[]{0x47, 0x40, 0x00};
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/segment_000.ts")))
                .thenReturn(new ByteArrayInputStream(ts));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/segment_000.ts");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("video/MP2T", response.getContentType());
        assertEquals(3, response.getContentAsByteArray().length);
    }

    @Test
    void shouldReturn403WhenFileIdTampered() throws Exception {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/aaaaaaaaaaaaaaaa/index.m3u8");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void shouldReturn404WhenObjectMissing() throws Exception {
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/index.m3u8"))).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", SIGNED_PATH);
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(404, response.getStatus());
    }

    @Test
    void shouldStreamAbrMasterAndRewriteVariantUri() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/master.m3u8"));

        String signedPath = "/hls/" + FILE_ID + "/master.m3u8";
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(signedPath, e, 0);
        String playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n360p/index.m3u8\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/master.m3u8")))
                .thenReturn(new ByteArrayInputStream(playlist.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", signedPath);
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("360p/index.m3u8?e=" + e + "&exper=0&sign=" + sign));
    }

    @Test
    void shouldAllowVariantSegmentUnderMasterSignature() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/master.m3u8"));

        String signedPath = "/hls/" + FILE_ID + "/master.m3u8";
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(signedPath, e, 0);
        byte[] ts = new byte[]{0x47, 0x40, 0x00};
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/360p/segment_000.ts")))
                .thenReturn(new ByteArrayInputStream(ts));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/360p/segment_000.ts");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("video/MP2T", response.getContentType());
    }

    @Test
    void shouldRejectMasterSignatureWhenMediaIsSinglePlaylist() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String masterSign = playSignService.sign("/hls/" + FILE_ID + "/master.m3u8", e, 0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", SIGNED_PATH);
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", masterSign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void l2ShouldAllowPreviewPlaylistAndRewrite() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);
        String preview = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n#EXT-X-ENDLIST\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/preview.m3u8")))
                .thenReturn(new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", PREVIEW_PATH);
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "120");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterL2.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("segment_000.ts?e=" + e + "&exper=120&sign=" + sign));
    }

    @Test
    void l2ShouldAllowListedTsWithPreviewSign() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);
        String preview = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n#EXT-X-ENDLIST\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/preview.m3u8")))
                .thenReturn(new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8)));
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/segment_000.ts")))
                .thenReturn(new ByteArrayInputStream(new byte[]{0x47}));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/segment_000.ts");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "120");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterL2.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void l2ShouldRejectLaterTsWithPreviewSign() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);
        String preview = "#EXTM3U\n#EXTINF:4.0,\nsegment_000.ts\n#EXT-X-ENDLIST\n";
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/preview.m3u8")))
                .thenReturn(new ByteArrayInputStream(preview.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/segment_050.ts");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "120");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterL2.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void l2ShouldRejectMasterWithPreviewSign() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/master.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(PREVIEW_PATH, e, 120);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/master.m3u8");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "120");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterL2.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void l2FullSignShouldStillPlayComplete() throws Exception {
        when(mediaMapper.findByFileId(FILE_ID)).thenReturn(mediaWithUrl("hls/" + FILE_ID + "/index.m3u8"));
        long e = playSignService.nowEpoch() + 600;
        String sign = playSignService.sign(SIGNED_PATH, e, 0);
        when(minioStorage.openStream(eq("hls/" + FILE_ID + "/segment_050.ts")))
                .thenReturn(new ByteArrayInputStream(new byte[]{0x47}));

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/hls/" + FILE_ID + "/segment_050.ts");
        request.setParameter("e", String.valueOf(e));
        request.setParameter("exper", "0");
        request.setParameter("sign", sign);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterL2.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    private static Media mediaWithUrl(String mediaUrl) {
        Media media = new Media();
        media.setFileId(FILE_ID);
        media.setMediaUrl(mediaUrl);
        return media;
    }
}
