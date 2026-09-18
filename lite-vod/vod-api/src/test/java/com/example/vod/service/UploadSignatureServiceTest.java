package com.example.vod.service;

import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadSignatureServiceTest {

    private MediaMapper mediaMapper;
    private MinioStorage minioStorage;
    private UploadSignatureService service;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        minioStorage = mock(MinioStorage.class);
        service = new UploadSignatureService(mediaMapper, minioStorage);
    }

    @Test
    void createShouldInsertUploadingMediaAndReturnPresignedUrl() {
        when(minioStorage.presignedPut(any(String.class), eq(Duration.ofMinutes(30))))
                .thenReturn("http://localhost:9000/vod/raw/test/source.mp4?X-Amz-Algorithm=...");

        UploadSignatureResponse response = service.create();

        assertNotNull(response.fileId());
        assertEquals(32, response.fileId().length());
        assertTrue(response.fileId().matches("^[0-9a-f]+$"));
        assertEquals("raw/" + response.fileId() + "/source.mp4", response.objectKey());
        assertNotNull(response.uploadUrl());
        assertTrue(response.expireAt() > Instant.now().getEpochSecond());
        assertTrue(response.expireAt() <= Instant.now().plusSeconds(30 * 60 + 5).getEpochSecond());

        ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
        verify(mediaMapper).insert(captor.capture());
        Media inserted = captor.getValue();
        assertEquals(MediaStatus.UPLOADING, inserted.getStatus());
        assertEquals(response.objectKey(), inserted.getObjectKey());
        assertEquals(response.fileId(), inserted.getFileId());
    }
}
