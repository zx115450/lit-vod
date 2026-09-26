package com.example.vod.service;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.controller.dto.PlaySignatureResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaySignatureServiceTest {

    private MediaMapper mediaMapper;
    private PlaySignService playSignService;
    private MinioStorage minioStorage;
    private PlaySignatureService serviceL2Off;
    private PlaySignatureService serviceL2On;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        playSignService = mock(PlaySignService.class);
        minioStorage = mock(MinioStorage.class);
        when(playSignService.publicBase()).thenReturn("http://localhost");
        when(playSignService.ttlSeconds()).thenReturn(3600L);
        when(playSignService.nowEpoch()).thenReturn(1710000000L);
        serviceL2Off = new PlaySignatureService(
                mediaMapper, playSignService, new PreviewProperties(false, 120), minioStorage);
        serviceL2On = new PlaySignatureService(
                mediaMapper, playSignService, new PreviewProperties(true, 120), minioStorage);
    }

    @Test
    void signShouldReturnUrlForFinishedMedia() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(playSignService.sign(eq("/hls/" + fileId + "/index.m3u8"), eq(1710003600L), eq(300)))
                .thenReturn("deadbeef");

        PlaySignatureResponse resp = serviceL2Off.sign(fileId, 300);

        assertEquals(fileId, resp.fileId());
        assertEquals("deadbeef", resp.signature());
        assertEquals(1710003600L, resp.expireAt());
        assertTrue(resp.playUrl().startsWith("http://localhost/hls/" + fileId + "/index.m3u8?"));
        assertTrue(resp.playUrl().contains("e=1710003600"));
        assertTrue(resp.playUrl().contains("exper=300"));
        assertTrue(resp.playUrl().contains("sign=deadbeef"));
    }

    @Test
    void signShouldDefaultExperToZero() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(playSignService.sign(anyString(), anyLong(), eq(0))).thenReturn("cafe");

        PlaySignatureResponse resp = serviceL2Off.sign(fileId, 0);

        assertTrue(resp.playUrl().contains("exper=0"));
    }

    @Test
    void signShouldRejectNegativeExperAsZero() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(playSignService.sign(anyString(), anyLong(), eq(0))).thenReturn("cafe");

        PlaySignatureResponse resp = serviceL2Off.sign(fileId, -5);

        assertTrue(resp.playUrl().contains("exper=0"));
    }

    @Test
    void signShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> serviceL2Off.sign("missing", 0));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void signShouldThrow400WhenFileIdBlank() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> serviceL2Off.sign("  ", 0));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void signShouldThrow400WhenMediaNotFinished() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(uploadingMedia(fileId));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> serviceL2Off.sign(fileId, 0));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("not processed"));
    }

    @Test
    void signShouldThrow400WhenMediaProcessing() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(processingMedia(fileId));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> serviceL2Off.sign(fileId, 0));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void signShouldStripTrailingSlashFromBase() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(playSignService.publicBase()).thenReturn("http://localhost/");
        when(playSignService.sign(anyString(), anyLong(), anyInt())).thenReturn("s");

        PlaySignatureResponse resp = serviceL2Off.sign(fileId, 0);

        assertFalse(resp.playUrl().contains("//hls"), "no double slash after base");
        assertTrue(resp.playUrl().startsWith("http://localhost/hls/"));
    }

    @Test
    void signShouldPointToMasterWhenMediaUrlIsMaster() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId);
        media.setMediaUrl("hls/" + fileId + "/master.m3u8");
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(playSignService.sign(eq("/hls/" + fileId + "/master.m3u8"), anyLong(), anyInt())).thenReturn("cafe");

        PlaySignatureResponse resp = serviceL2Off.sign(fileId, 0);

        assertTrue(resp.playUrl().startsWith("http://localhost/hls/" + fileId + "/master.m3u8?"));
    }

    @Test
    void signL2ShouldPointToPreviewWhenExperPositiveAndPreviewExists() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(minioStorage.exists(ObjectKeys.hlsPreview(fileId))).thenReturn(true);
        when(playSignService.sign(eq("/hls/" + fileId + "/preview.m3u8"), eq(1710003600L), eq(300)))
                .thenReturn("preview-sign");

        PlaySignatureResponse resp = serviceL2On.sign(fileId, 300);

        assertTrue(resp.playUrl().startsWith("http://localhost/hls/" + fileId + "/preview.m3u8?"));
        assertTrue(resp.playUrl().contains("exper=300"));
        assertTrue(resp.playUrl().contains("sign=preview-sign"));
    }

    @Test
    void signL2ShouldFallbackToFullWhenPreviewMissing() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(minioStorage.exists(ObjectKeys.hlsPreview(fileId))).thenReturn(false);
        when(playSignService.sign(eq("/hls/" + fileId + "/index.m3u8"), anyLong(), eq(300)))
                .thenReturn("l1-sign");

        PlaySignatureResponse resp = serviceL2On.sign(fileId, 300);

        assertTrue(resp.playUrl().startsWith("http://localhost/hls/" + fileId + "/index.m3u8?"));
    }

    @Test
    void signL2ShouldKeepFullPathWhenExperZero() {
        String fileId = "f7c2a1b0e9d84f6a";
        when(mediaMapper.findByFileId(fileId)).thenReturn(finishedMedia(fileId));
        when(minioStorage.exists(ObjectKeys.hlsPreview(fileId))).thenReturn(true);
        when(playSignService.sign(eq("/hls/" + fileId + "/index.m3u8"), anyLong(), eq(0)))
                .thenReturn("full-sign");

        PlaySignatureResponse resp = serviceL2On.sign(fileId, 0);

        assertTrue(resp.playUrl().startsWith("http://localhost/hls/" + fileId + "/index.m3u8?"));
    }

    private Media finishedMedia(String fileId) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.FINISHED);
        media.setMediaUrl("hls/" + fileId + "/index.m3u8");
        return media;
    }

    private Media uploadingMedia(String fileId) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.UPLOADING);
        return media;
    }

    private Media processingMedia(String fileId) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.PROCESSING);
        return media;
    }
}
