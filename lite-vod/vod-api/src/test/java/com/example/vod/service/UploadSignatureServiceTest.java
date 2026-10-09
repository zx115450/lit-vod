package com.example.vod.service;

import com.example.vod.common.IdGenerator;
import com.example.vod.config.UploadProperties;
import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.PartEtag;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import io.minio.messages.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadSignatureServiceTest {

    private MediaMapper mediaMapper;
    private MinioStorage minioStorage;
    private UploadProperties uploadProps;
    private UploadSignatureService service;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        minioStorage = mock(MinioStorage.class);
        uploadProps = new UploadProperties(true, 5L * 1024 * 1024, 64L * 1024 * 1024, 10L * 1024 * 1024);
        service = new UploadSignatureService(mediaMapper, minioStorage, uploadProps);
    }

    // ==================== 首期整对象上传 ====================

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
        assertEquals(AssetType.VIDEO, inserted.getAssetType());
        assertEquals("video/mp4", inserted.getMimeType());
        assertEquals(response.objectKey(), inserted.getObjectKey());
        assertEquals(response.fileId(), inserted.getFileId());
    }

    @Test
    void createDocumentShouldUseBinObjectKeyAndAssetType() {
        when(minioStorage.presignedPut(any(String.class), eq(Duration.ofMinutes(30))))
                .thenReturn("http://localhost:9000/vod/raw/test/source.bin?X-Amz-Algorithm=...");

        UploadSignatureResponse response = service.create(AssetType.DOCUMENT);

        assertEquals("raw/" + response.fileId() + "/source.bin", response.objectKey());
        ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
        verify(mediaMapper).insert(captor.capture());
        Media inserted = captor.getValue();
        assertEquals(AssetType.DOCUMENT, inserted.getAssetType());
        assertEquals("application/octet-stream", inserted.getMimeType());
    }

    @Test
    void createChapterShouldReject() {
        assertThrows(ResponseStatusException.class, () -> service.create(AssetType.CHAPTER));
        verify(mediaMapper, never()).insert(any());
    }

    // ==================== Multipart 初始化 ====================

    @Test
    void createMultipartShouldInsertMediaAndReturnAllPartUrls() {
        long contentLength = 25L * 1024 * 1024; // 25 MiB
        long partSize = 10L * 1024 * 1024;      // 10 MiB → 3 parts
        when(minioStorage.createMultipartUpload(anyString(), anyString())).thenReturn("upload-id-abc");
        when(minioStorage.presignedUploadPart(anyString(), eq("upload-id-abc"), anyInt(), any(Duration.class)))
                .thenAnswer(inv -> "http://minio/...?partNumber=" + inv.getArgument(2));

        MultipartUploadSignatureResponse resp = service.createMultipart(
                new MultipartUploadRequest("lesson01.mp4", "video/mp4", contentLength, partSize));

        assertNotNull(resp.fileId());
        assertEquals("raw/" + resp.fileId() + "/source.mp4", resp.objectKey());
        assertEquals("upload-id-abc", resp.uploadId());
        assertEquals(partSize, resp.partSize());
        assertEquals(3, resp.partCount());
        assertEquals(3, resp.parts().size());
        assertEquals(1, resp.parts().get(0).partNumber());
        assertEquals(2, resp.parts().get(1).partNumber());
        assertEquals(3, resp.parts().get(2).partNumber());
        assertTrue(resp.parts().get(0).uploadUrl().contains("partNumber=1"));
        assertTrue(resp.expireAt() > Instant.now().getEpochSecond());

        ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
        verify(mediaMapper).insert(captor.capture());
        assertEquals(MediaStatus.UPLOADING, captor.getValue().getStatus());
        assertEquals(AssetType.VIDEO, captor.getValue().getAssetType());
    }

    @Test
    void createMultipartShouldRespectDocumentAssetType() {
        long contentLength = 12L * 1024 * 1024;
        long partSize = 10L * 1024 * 1024;
        when(minioStorage.createMultipartUpload(anyString(), anyString())).thenReturn("upload-doc");
        when(minioStorage.presignedUploadPart(anyString(), eq("upload-doc"), anyInt(), any(Duration.class)))
                .thenReturn("http://minio/...");

        MultipartUploadSignatureResponse resp = service.createMultipart(
                new MultipartUploadRequest("book.md", "text/markdown", contentLength, partSize, "DOCUMENT"));

        assertEquals("raw/" + resp.fileId() + "/source.bin", resp.objectKey());
        ArgumentCaptor<Media> captor = ArgumentCaptor.forClass(Media.class);
        verify(mediaMapper).insert(captor.capture());
        assertEquals(AssetType.DOCUMENT, captor.getValue().getAssetType());
        assertEquals("text/markdown", captor.getValue().getMimeType());
    }

    @Test
    void createMultipartShouldUseDefaultPartSizeWhenNotProvided() {
        long contentLength = 25L * 1024 * 1024;
        long expectedPartSize = 10L * 1024 * 1024; // default
        when(minioStorage.createMultipartUpload(anyString(), anyString())).thenReturn("uid");
        when(minioStorage.presignedUploadPart(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn("http://minio/...");

        MultipartUploadSignatureResponse resp = service.createMultipart(
                new MultipartUploadRequest("lesson01.mp4", null, contentLength, 0));

        assertEquals(expectedPartSize, resp.partSize());
        assertEquals(3, resp.partCount());
    }

    @Test
    void createMultipartShouldThrow400WhenPartSizeTooSmall() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createMultipart(new MultipartUploadRequest("f.mp4", "video/mp4", 100_000_000, 1024)));
        assertEquals(400, ex.getStatusCode().value());
        verify(mediaMapper, never()).insert(any());
    }

    @Test
    void createMultipartShouldThrow400WhenPartCountExceeds10000() {
        // partSize=5MiB, contentLength=500GB → > 10000 parts
        long partSize = 5L * 1024 * 1024;
        long contentLength = 500L * 1024 * 1024 * 1024;
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createMultipart(new MultipartUploadRequest("big.mp4", "video/mp4", contentLength, partSize)));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createMultipartShouldThrow501WhenDisabled() {
        UploadProperties disabled = new UploadProperties(false, 5L * 1024 * 1024, 64L * 1024 * 1024, 10L * 1024 * 1024);
        UploadSignatureService svc = new UploadSignatureService(mediaMapper, minioStorage, disabled);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                svc.createMultipart(new MultipartUploadRequest("f.mp4", "video/mp4", 100_000_000, 10_000_000)));
        assertEquals(501, ex.getStatusCode().value());
    }

    // ==================== Multipart Complete ====================

    @Test
    @SuppressWarnings("unchecked")
    void completeShouldCallMinioCompleteWithSortedParts() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = new Media();
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.UPLOADING);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        List<PartEtag> parts = List.of(
                new PartEtag(3, "\"etag3\""),
                new PartEtag(1, "\"etag1\""),
                new PartEtag(2, "\"etag2\"")
        );
        service.complete(fileId, new CompleteMultipartRequest("uid", parts));

        ArgumentCaptor<List<Part>> captor = ArgumentCaptor.forClass(List.class);
        verify(minioStorage).completeMultipartUpload(eq(media.getObjectKey()), eq("uid"), captor.capture());
        List<Part> sent = captor.getValue();
        assertEquals(3, sent.size());
        assertEquals(1, sent.get(0).partNumber());
        assertEquals(2, sent.get(1).partNumber());
        assertEquals(3, sent.get(2).partNumber());
        // Part 构造时 etag 原样传入，但 Part.etag() 可能去除引号
        assertNotNull(sent.get(0).etag());
    }

    @Test
    void completeShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.complete("missing", new CompleteMultipartRequest("uid", List.of())));
        assertEquals(404, ex.getStatusCode().value());
        verify(minioStorage, never()).completeMultipartUpload(anyString(), anyString(), any());
    }

    @Test
    void completeShouldThrow409WhenMediaNotUploading() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = new Media();
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.FINISHED);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.complete(fileId, new CompleteMultipartRequest("uid", List.of())));
        assertEquals(409, ex.getStatusCode().value());
    }

    // ==================== Multipart Abort ====================

    @Test
    void abortShouldCallMinioAbort() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = new Media();
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.UPLOADING);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        service.abort(fileId, new AbortMultipartRequest("uid"));
        verify(minioStorage).abortMultipartUpload(media.getObjectKey(), "uid");
    }

    @Test
    void abortShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.abort("missing", new AbortMultipartRequest("uid")));
        assertEquals(404, ex.getStatusCode().value());
    }
}
