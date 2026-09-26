package com.example.vod.service;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.messaging.RabbitConfig;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private MediaMapper mediaMapper;
    private MediaTaskMapper mediaTaskMapper;
    private MinioStorage minioStorage;
    private RabbitTemplate rabbitTemplate;
    private AbrProperties abrProperties;
    private PreviewProperties previewProperties;
    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        mediaTaskMapper = mock(MediaTaskMapper.class);
        minioStorage = mock(MinioStorage.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        abrProperties = mock(AbrProperties.class);
        previewProperties = new PreviewProperties(true, 30, 1800);
        when(abrProperties.progressiveEnabled()).thenReturn(false);
        mediaService = new MediaService(
                mediaMapper, mediaTaskMapper, minioStorage, rabbitTemplate, abrProperties, previewProperties);
    }

    @Test
    void commitShouldCreateTaskAndUpdateMediaToProcessing() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "lesson01.mp4");

        assertEquals(fileId, dto.fileId());
        assertEquals("lesson01.mp4", dto.filename());
        assertEquals(2048L, dto.size());
        assertEquals(MediaStatus.PROCESSING, dto.status());
        assertEquals("处理中", dto.statusText());

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        MediaTask inserted = taskCaptor.getValue();
        assertEquals(media.getId(), inserted.getMediaId());
        assertEquals(fileId, inserted.getFileId());
        assertEquals(MediaTaskType.PROCEDURE, inserted.getType());
        assertEquals(MediaTaskStatus.PENDING, inserted.getStatus());
        assertEquals(0, inserted.getAttempt());

        verify(mediaMapper).updateUploaded(fileId, "lesson01.mp4", 2048L, MediaStatus.PROCESSING, 30);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new ProcedureTaskMessage(fileId, media.getId(), media.getObjectKey(), inserted.getId(),
                        ProcedureTaskMessage.TaskType.FULL, false, 30)));
    }

    @Test
    void commitShouldUseUploaderPreviewSeconds() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L);
        processing.setPreviewSeconds(120);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "lesson01.mp4", false, 120);

        assertEquals(120, dto.previewSeconds());
        verify(mediaMapper).updateUploaded(fileId, "lesson01.mp4", 2048L, MediaStatus.PROCESSING, 120);

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new ProcedureTaskMessage(fileId, media.getId(), media.getObjectKey(),
                        taskCaptor.getValue().getId(),
                        ProcedureTaskMessage.TaskType.FULL, false, 120)));
    }

    @Test
    void commitShouldDispatchFastTaskWhenProgressiveEnabled() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L);

        when(abrProperties.progressiveEnabled()).thenReturn(true);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "lesson01.mp4");

        assertEquals(MediaStatus.PROCESSING, dto.status());

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        MediaTask inserted = taskCaptor.getValue();

        verify(mediaMapper).updateUploaded(fileId, "lesson01.mp4", 2048L, MediaStatus.PROCESSING, 30);
        verify(mediaMapper).updateLadderFinished(fileId, MediaStatus.PROCESSING, null,
                com.example.vod.common.domain.media.LadderStatus.PENDING.code());

        ProcedureTaskMessage expected = new ProcedureTaskMessage(
                fileId, media.getId(), media.getObjectKey(), inserted.getId(),
                ProcedureTaskMessage.TaskType.FAST, true, 30);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(expected));
    }

    @Test
    void commitShouldDispatchFastTaskWhenProgressiveOverrideTrue() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L);

        when(abrProperties.progressiveEnabled()).thenReturn(false);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "lesson01.mp4", true);

        assertEquals(MediaStatus.PROCESSING, dto.status());

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        MediaTask inserted = taskCaptor.getValue();

        verify(mediaMapper).updateLadderFinished(fileId, MediaStatus.PROCESSING, null,
                com.example.vod.common.domain.media.LadderStatus.PENDING.code());

        ProcedureTaskMessage expected = new ProcedureTaskMessage(
                fileId, media.getId(), media.getObjectKey(), inserted.getId(),
                ProcedureTaskMessage.TaskType.FAST, true, 30);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(expected));
    }

    @Test
    void commitShouldDispatchFullTaskWhenProgressiveOverrideFalse() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L);

        when(abrProperties.progressiveEnabled()).thenReturn(true);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        mediaService.commit(fileId, "lesson01.mp4", false);

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        MediaTask inserted = taskCaptor.getValue();

        verify(mediaMapper, never()).updateLadderFinished(anyString(), any(), any(), anyInt());

        ProcedureTaskMessage expected = new ProcedureTaskMessage(
                fileId, media.getId(), media.getObjectKey(), inserted.getId(),
                ProcedureTaskMessage.TaskType.FULL, false, 30);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(expected));
    }

    @Test
    void commitShouldReturnExistingDtoWhenAlreadyFinished() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        MediaDto dto = mediaService.commit(fileId, "newname.mp4");

        assertEquals(MediaStatus.FINISHED, dto.status());
        assertEquals("已完成", dto.statusText());
        verify(minioStorage, never()).head(anyString());
        verify(mediaTaskMapper, never()).findPendingByMediaId(anyLong());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldReturnExistingDtoWhenPendingTaskExists() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L);
        MediaTask pending = new MediaTask(10L, media.getId(), fileId,
                MediaTaskType.PROCEDURE, MediaTaskStatus.PENDING, 0, null, null, null);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of(pending));

        MediaDto dto = mediaService.commit(fileId, "newname.mp4");

        assertEquals(MediaStatus.PROCESSING, dto.status());
        assertEquals("处理中", dto.statusText());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit("missing", "x.mp4"));
        assertEquals(404, ex.getStatusCode().value());
        verify(minioStorage, never()).head(any());
    }

    @Test
    void commitShouldThrow400WhenObjectNotInMinio() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "x.mp4"));
        assertEquals(400, ex.getStatusCode().value());
        verify(mediaMapper, never()).updateUploaded(anyString(), anyString(), anyLong(), any(), any());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldThrow400WhenSizeExceedsLimit() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(3L * 1024 * 1024 * 1024); // 3 GB

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "x.mp4"));
        assertEquals(400, ex.getStatusCode().value());
        verify(mediaMapper, never()).updateUploaded(anyString(), anyString(), anyLong(), any(), any());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void detailShouldReturnMediaDto() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        MediaDto dto = mediaService.detail(fileId);

        assertEquals(fileId, dto.fileId());
        assertEquals("lesson01.mp4", dto.filename());
        assertEquals("处理中", dto.statusText());
    }

    @Test
    void detailShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.detail("missing"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void deleteShouldRemoveObjectsAndRowsWhenFinished() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        mediaService.delete(fileId);

        // 先删对象，按 raw/hls 前缀 + cover
        verify(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.hlsPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.cover(fileId));
        // 再删库：先任务行，再媒资行
        verify(mediaTaskMapper).deleteByMediaId(media.getId());
        verify(mediaMapper).deleteByFileId(fileId);
    }

    @Test
    void deleteShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete("missing"));
        assertEquals(404, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void deleteShouldThrow409WhenMediaProcessing() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete(fileId));
        assertEquals(409, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void deleteShouldThrow409WhenRunningTaskExists() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        MediaTask running = new MediaTask(10L, media.getId(), fileId,
                MediaTaskType.PROCEDURE, MediaTaskStatus.RUNNING, 1, null, null, null);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of(running));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete(fileId));
        assertEquals(409, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void deleteShouldNotTouchDbWhenObjectDeleteFails() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());
        // raw 前缀删除即抛异常，模拟对象删失败
        org.mockito.Mockito.doThrow(new IllegalStateException("remove prefix failed"))
                .when(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));

        assertThrows(IllegalStateException.class, () -> mediaService.delete(fileId));
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void listShouldReturnPaginatedResult() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.countByFilename("lesson")).thenReturn(1L);
        when(mediaMapper.pageByFilename("lesson", 0, 10)).thenReturn(List.of(media));

        PageResult<MediaDto> result = mediaService.list("lesson", 1, 10);

        assertEquals(1, result.total());
        assertEquals(1, result.pageNo());
        assertEquals(10, result.pageSize());
        assertEquals(1, result.pages());
        assertEquals(1, result.records().size());
        assertEquals("lesson01.mp4", result.records().get(0).filename());
    }

    @Test
    void listShouldClampPageParams() {
        when(mediaMapper.countByFilename(null)).thenReturn(0L);

        PageResult<MediaDto> result = mediaService.list(null, 0, 200);

        assertEquals(0, result.total());
        assertEquals(1, result.pageNo());
        assertEquals(100, result.pageSize());
        assertEquals(0, result.records().size());
    }

    private Media uploadingMedia(String fileId) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.UPLOADING);
        return media;
    }

    private Media processingMedia(String fileId, String filename, long size) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setFilename(filename);
        media.setSize(size);
        media.setStatus(MediaStatus.PROCESSING);
        return media;
    }

    private Media finishedMedia(String fileId, String filename, long size) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setFilename(filename);
        media.setSize(size);
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }
}
