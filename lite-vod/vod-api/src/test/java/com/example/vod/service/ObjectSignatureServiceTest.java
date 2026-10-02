package com.example.vod.service;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.controller.dto.ObjectSignatureResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectSignatureServiceTest {

    private MediaMapper mediaMapper;
    private MinioStorage minioStorage;
    private ObjectSignatureService service;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        minioStorage = mock(MinioStorage.class);
        service = new ObjectSignatureService(mediaMapper, minioStorage);
    }

    @Test
    void shouldSignChapter() {
        Media media = chapter("chap-1", "chap/src/001.md");
        when(mediaMapper.findByFileId("chap-1")).thenReturn(media);
        when(minioStorage.presignedGet(eq("chap/src/001.md"), eq(Duration.ofSeconds(60))))
                .thenReturn("http://minio/vod/chap/src/001.md?X-Amz-Signature=abc");

        ObjectSignatureResponse resp = service.sign("chap-1", null);

        assertEquals("chap-1", resp.fileId());
        assertEquals(AssetType.CHAPTER, resp.assetType());
        assertEquals("chap/src/001.md", resp.objectKey());
        assertTrue(resp.objectUrl().contains("X-Amz-Signature"));
        assertTrue(resp.expireAt() > 0);
        verify(minioStorage).presignedGet("chap/src/001.md", Duration.ofSeconds(60));
    }

    @Test
    void shouldClampTtl() {
        Media media = chapter("chap-1", "chap/src/001.md");
        when(mediaMapper.findByFileId("chap-1")).thenReturn(media);
        when(minioStorage.presignedGet(any(), any())).thenReturn("http://u");

        service.sign("chap-1", 9999);

        verify(minioStorage).presignedGet("chap/src/001.md", Duration.ofSeconds(600));
    }

    @Test
    void shouldSignImage() {
        Media media = new Media();
        media.setFileId("img-1");
        media.setAssetType(AssetType.IMAGE);
        media.setObjectKey("img/img-1/cover.jpg");
        media.setStatus(MediaStatus.FINISHED);
        when(mediaMapper.findByFileId("img-1")).thenReturn(media);
        when(minioStorage.presignedGet(eq("img/img-1/cover.jpg"), eq(Duration.ofSeconds(60))))
                .thenReturn("http://minio/vod/img/img-1/cover.jpg?X-Amz-Signature=abc");

        ObjectSignatureResponse resp = service.sign("img-1", null);

        assertEquals(AssetType.IMAGE, resp.assetType());
        assertEquals("img/img-1/cover.jpg", resp.objectKey());
        assertTrue(resp.objectUrl().contains("X-Amz-Signature"));
    }

    @Test
    void shouldRejectVideo() {
        Media media = new Media();
        media.setFileId("v1");
        media.setAssetType(AssetType.VIDEO);
        media.setObjectKey("raw/v1/source.mp4");
        media.setStatus(MediaStatus.FINISHED);
        when(mediaMapper.findByFileId("v1")).thenReturn(media);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sign("v1", 60));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void should404WhenMissing() {
        when(mediaMapper.findByFileId("x")).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.sign("x", 60));
        assertEquals(404, ex.getStatusCode().value());
    }

    private static Media chapter(String fileId, String objectKey) {
        Media media = new Media();
        media.setFileId(fileId);
        media.setAssetType(AssetType.CHAPTER);
        media.setObjectKey(objectKey);
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }
}
