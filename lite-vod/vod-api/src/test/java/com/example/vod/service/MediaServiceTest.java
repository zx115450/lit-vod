package com.example.vod.service;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.common.domain.media.ProcedureDeadLetterMapper;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.common.messaging.DocumentSplitTaskMessage;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.common.messaging.RabbitConfig;
import com.example.vod.common.messaging.ThumbnailTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.controller.dto.ChapterDto;
import com.example.vod.controller.dto.ChaptersResponse;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.service.commit.ChapterCommitStrategy;
import com.example.vod.service.commit.CommitStrategyRegistry;
import com.example.vod.service.commit.DocumentCommitStrategy;
import com.example.vod.service.commit.ImageCommitStrategy;
import com.example.vod.service.commit.VideoCommitStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private ProcedureDeadLetterMapper deadLetterMapper;
    private MinioStorage minioStorage;
    private RabbitTemplate rabbitTemplate;
    private AbrProperties abrProperties;
    private PreviewProperties previewProperties;
    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        mediaTaskMapper = mock(MediaTaskMapper.class);
        deadLetterMapper = mock(ProcedureDeadLetterMapper.class);
        minioStorage = mock(MinioStorage.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        abrProperties = mock(AbrProperties.class);
        previewProperties = new PreviewProperties(true, 30, 1800);
        when(abrProperties.progressiveEnabled()).thenReturn(false);

        CommitStrategyRegistry registry = new CommitStrategyRegistry(List.of(
                new VideoCommitStrategy(mediaMapper, mediaTaskMapper, rabbitTemplate, abrProperties, previewProperties),
                new DocumentCommitStrategy(mediaMapper, mediaTaskMapper, rabbitTemplate),
                new ChapterCommitStrategy(),
                new ImageCommitStrategy(mediaMapper, mediaTaskMapper, rabbitTemplate)
        ));
        mediaService = new MediaService(
                mediaMapper, mediaTaskMapper, deadLetterMapper, minioStorage, registry);
    }

    @Test
    void commitShouldCreateTaskAndUpdateMediaToProcessing() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);

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
    void commitDocumentShouldDispatchSplitChapterNotProcedure() {
        String fileId = "doc-file-001";
        Media media = uploadingMedia(fileId, AssetType.DOCUMENT);
        media.setObjectKey("raw/" + fileId + "/source.bin");
        Media processing = processingMedia(fileId, "redis.md", 512L, AssetType.DOCUMENT);
        processing.setObjectKey(media.getObjectKey());

        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(512L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "redis.md", null, null, AssetType.DOCUMENT, SplitRule.MARKDOWN);

        assertEquals(AssetType.DOCUMENT, dto.assetType());
        assertEquals(MediaStatus.PROCESSING, dto.status());
        assertNull(dto.mediaUrl());

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        MediaTask inserted = taskCaptor.getValue();
        assertEquals(MediaTaskType.SPLIT_CHAPTER, inserted.getType());
        assertEquals("{\"splitRule\":\"MARKDOWN\"}", inserted.getPayload());

        verify(mediaMapper).updateUploaded(fileId, "redis.md", 512L, MediaStatus.PROCESSING, 0);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.DOCUMENT_SPLIT_ROUTING_KEY),
                eq(new DocumentSplitTaskMessage(fileId, media.getId(), media.getObjectKey(),
                        inserted.getId(), SplitRule.MARKDOWN)));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                any(ProcedureTaskMessage.class));
    }

    @Test
    void commitChapterShouldReturn400() {
        String fileId = "chap-001";
        Media media = uploadingMedia(fileId, AssetType.CHAPTER);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(100L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "c.md", null, null, null, null));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitImageShouldCreateThumbnailTaskWithoutHls() {
        String fileId = "img-001";
        Media media = uploadingMedia(fileId, AssetType.IMAGE);
        media.setObjectKey(ObjectKeys.raw(fileId, "jpg"));
        Media processing = processingMedia(fileId, "cover.png", 100L, AssetType.IMAGE);
        processing.setObjectKey(media.getObjectKey());
        processing.setMediaUrl(null);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(100L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "cover.png", null, null, AssetType.IMAGE, null);

        assertEquals(AssetType.IMAGE, dto.assetType());
        assertEquals(MediaStatus.PROCESSING, dto.status());
        assertNull(dto.mediaUrl());
        verify(mediaMapper).updateUploaded(fileId, "cover.png", 100L, MediaStatus.PROCESSING, 0);

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        assertEquals(MediaTaskType.THUMBNAIL, taskCaptor.getValue().getType());
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.IMAGE_THUMBNAIL_ROUTING_KEY),
                eq(new ThumbnailTaskMessage(fileId, media.getId(), media.getObjectKey(),
                        taskCaptor.getValue().getId())));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                any(ProcedureTaskMessage.class));
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.DOCUMENT_SPLIT_ROUTING_KEY),
                any(DocumentSplitTaskMessage.class));
    }

    @Test
    void commitAudioShouldReturn501() {
        String fileId = "aud-001";
        Media media = uploadingMedia(fileId, AssetType.AUDIO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(100L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "a.mp3", null, null, null, null));
        assertEquals(501, ex.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldRejectAssetTypeMismatch() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(100L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "x.mp4", null, null, AssetType.DOCUMENT, null));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldUseUploaderPreviewSeconds() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);
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
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);

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
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new ProcedureTaskMessage(fileId, media.getId(), media.getObjectKey(), inserted.getId(),
                        ProcedureTaskMessage.TaskType.FAST, true, 30)));
    }

    @Test
    void commitShouldDispatchFastTaskWhenProgressiveOverrideTrue() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);

        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        MediaDto dto = mediaService.commit(fileId, "lesson01.mp4", true);

        assertEquals(MediaStatus.PROCESSING, dto.status());
        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new ProcedureTaskMessage(fileId, media.getId(), media.getObjectKey(),
                        taskCaptor.getValue().getId(),
                        ProcedureTaskMessage.TaskType.FAST, true, 30)));
    }

    @Test
    void commitShouldDispatchFullTaskWhenProgressiveOverrideFalse() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        Media processing = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);

        when(abrProperties.progressiveEnabled()).thenReturn(true);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media).thenReturn(processing);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(2048L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        mediaService.commit(fileId, "lesson01.mp4", false);

        ArgumentCaptor<MediaTask> taskCaptor = ArgumentCaptor.forClass(MediaTask.class);
        verify(mediaTaskMapper).insert(taskCaptor.capture());
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ROUTING_KEY),
                eq(new ProcedureTaskMessage(fileId, media.getId(), media.getObjectKey(),
                        taskCaptor.getValue().getId(),
                        ProcedureTaskMessage.TaskType.FULL, false, 30)));
        verify(mediaMapper, never()).updateLadderFinished(anyString(), any(), any(), any());
    }

    @Test
    void commitShouldReturnExistingDtoWhenAlreadyFinished() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        MediaDto dto = mediaService.commit(fileId, "newname.mp4");

        assertEquals("lesson01.mp4", dto.filename());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldReturnExistingDtoWhenPendingTaskExists() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        MediaTask pending = new MediaTask(10L, media.getId(), fileId,
                MediaTaskType.PROCEDURE, MediaTaskStatus.PENDING, 0, null, null, null, null);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(100L);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of(pending));

        MediaDto dto = mediaService.commit(fileId, "newname.mp4");

        assertEquals(fileId, dto.fileId());
        verify(mediaTaskMapper, never()).insert(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit("missing", "x.mp4"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void commitShouldThrow400WhenObjectNotInMinio() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "x.mp4"));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void commitShouldThrow400WhenSizeExceedsLimit() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = uploadingMedia(fileId, AssetType.VIDEO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(minioStorage.head(media.getObjectKey())).thenReturn(true);
        when(minioStorage.statSize(media.getObjectKey())).thenReturn(3L * 1024 * 1024 * 1024);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.commit(fileId, "x.mp4"));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void detailShouldReturnMediaDto() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        MediaDto dto = mediaService.detail(fileId);

        assertEquals(fileId, dto.fileId());
        assertEquals("lesson01.mp4", dto.filename());
        assertEquals(AssetType.VIDEO, dto.assetType());
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

        verify(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.hlsPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.cover(fileId));
        verify(deadLetterMapper).deleteByMediaId(media.getId());
        verify(mediaTaskMapper).deleteByMediaId(media.getId());
        verify(mediaMapper).deleteByFileId(fileId);
    }

    @Test
    void deleteDocumentShouldCascadeChaptersAndChapPrefix() {
        String fileId = "doc-source-1";
        Media parent = finishedDocument(fileId);
        Media c1 = chapterMedia(fileId, 1, "chap-aaa", "持久化", 100);
        Media c2 = chapterMedia(fileId, 2, "chap-bbb", "复制", 200);
        when(mediaMapper.findByFileId(fileId)).thenReturn(parent);
        when(mediaTaskMapper.findPendingByMediaId(parent.getId())).thenReturn(List.of());
        when(mediaMapper.findChaptersByParentFileId(fileId)).thenReturn(List.of(c1, c2));

        mediaService.delete(fileId);

        verify(minioStorage).removePrefix(ObjectKeys.chapterPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.hlsPrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.cover(fileId));
        verify(minioStorage, never()).removePrefix(ObjectKeys.imagePrefix(fileId));
        verify(deadLetterMapper).deleteByMediaId(c1.getId());
        verify(deadLetterMapper).deleteByMediaId(c2.getId());
        verify(mediaTaskMapper).deleteByMediaId(c1.getId());
        verify(mediaTaskMapper).deleteByMediaId(c2.getId());
        verify(mediaMapper).deleteByParentFileId(fileId);
        verify(deadLetterMapper).deleteByMediaId(parent.getId());
        verify(mediaTaskMapper).deleteByMediaId(parent.getId());
        verify(mediaMapper).deleteByFileId(fileId);
    }

    @Test
    void deleteDocumentShouldThrow409WhenProcessing() {
        String fileId = "doc-processing";
        Media media = processingMedia(fileId, "redis.md", 10L, AssetType.DOCUMENT);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete(fileId));
        assertEquals(409, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
        verify(mediaMapper, never()).deleteByParentFileId(anyString());
    }

    @Test
    void deleteDocumentShouldNotTouchDbWhenChapPrefixDeleteFails() {
        String fileId = "doc-source-1";
        Media parent = finishedDocument(fileId);
        when(mediaMapper.findByFileId(fileId)).thenReturn(parent);
        when(mediaTaskMapper.findPendingByMediaId(parent.getId())).thenReturn(List.of());
        when(mediaMapper.findChaptersByParentFileId(fileId)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("remove chap failed"))
                .when(minioStorage).removePrefix(ObjectKeys.chapterPrefix(fileId));

        assertThrows(IllegalStateException.class, () -> mediaService.delete(fileId));
        verify(mediaMapper, never()).deleteByParentFileId(anyString());
        verify(mediaMapper, never()).deleteByFileId(anyString());
        verify(minioStorage, never()).removePrefix(ObjectKeys.rawPrefix(fileId));
    }

    @Test
    void deleteChapterShouldRemoveOnlyThatObject() {
        String parentId = "doc-source-1";
        Media chapter = chapterMedia(parentId, 1, "chap-aaa", "持久化", 100);
        when(mediaMapper.findByFileId(chapter.getFileId())).thenReturn(chapter);
        when(mediaTaskMapper.findPendingByMediaId(chapter.getId())).thenReturn(List.of());

        mediaService.delete(chapter.getFileId());

        verify(minioStorage).removePrefix(chapter.getObjectKey());
        verify(minioStorage, never()).removePrefix(ObjectKeys.chapterPrefix(parentId));
        verify(mediaMapper, never()).deleteByParentFileId(anyString());
        verify(mediaMapper).deleteByFileId(chapter.getFileId());
        verify(minioStorage).removePrefix(ObjectKeys.rawPrefix(chapter.getFileId()));
    }

    @Test
    void deleteImageShouldRemoveThumbnailPrefix() {
        String fileId = "img-001";
        Media media = finishedImage(fileId);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());

        mediaService.delete(fileId);

        verify(minioStorage).removePrefix(ObjectKeys.imagePrefix(fileId));
        verify(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));
        verify(minioStorage, never()).removePrefix(ObjectKeys.chapterPrefix(fileId));
        verify(mediaMapper, never()).deleteByParentFileId(anyString());
        verify(mediaMapper).deleteByFileId(fileId);
    }

    @Test
    void deleteShouldThrow404WhenMediaNotFound() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete("missing"));
        assertEquals(404, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
        verify(deadLetterMapper, never()).deleteByMediaId(anyLong());
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void deleteShouldThrow409WhenMediaProcessing() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete(fileId));
        assertEquals(409, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
    }

    @Test
    void deleteShouldThrow409WhenRunningTaskExists() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        MediaTask running = new MediaTask(10L, media.getId(), fileId,
                MediaTaskType.PROCEDURE, MediaTaskStatus.RUNNING, 1, null, null, null, null);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of(running));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> mediaService.delete(fileId));
        assertEquals(409, ex.getStatusCode().value());
        verify(minioStorage, never()).removePrefix(anyString());
    }

    @Test
    void deleteShouldNotTouchDbWhenObjectDeleteFails() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = finishedMedia(fileId, "lesson01.mp4", 2048L);
        when(mediaMapper.findByFileId(fileId)).thenReturn(media);
        when(mediaTaskMapper.findPendingByMediaId(media.getId())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("remove prefix failed"))
                .when(minioStorage).removePrefix(ObjectKeys.rawPrefix(fileId));

        assertThrows(IllegalStateException.class, () -> mediaService.delete(fileId));
        verify(deadLetterMapper, never()).deleteByMediaId(anyLong());
        verify(mediaTaskMapper, never()).deleteByMediaId(anyLong());
        verify(mediaMapper, never()).deleteByFileId(anyString());
    }

    @Test
    void listShouldReturnPaginatedResult() {
        String fileId = "f7c2a1b0e9d84f6a";
        Media media = processingMedia(fileId, "lesson01.mp4", 2048L, AssetType.VIDEO);
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

    @Test
    void listChaptersShouldReturnSortedDtos() {
        String sourceId = "doc-source-1";
        Media parent = finishedDocument(sourceId);
        Media c1 = chapterMedia(sourceId, 1, "chap-aaa", "持久化", 100);
        Media c2 = chapterMedia(sourceId, 2, "chap-bbb", "复制", 200);
        when(mediaMapper.findByFileId(sourceId)).thenReturn(parent);
        when(mediaMapper.findChaptersByParentFileId(sourceId)).thenReturn(List.of(c1, c2));

        ChaptersResponse resp = mediaService.listChapters(sourceId);

        assertEquals(sourceId, resp.sourceFileId());
        assertEquals(2, resp.chapters().size());
        ChapterDto first = resp.chapters().get(0);
        assertEquals(1, first.chapterNo());
        assertEquals("持久化", first.title());
        assertEquals("chap-aaa", first.fileId());
        assertEquals(100, first.wordCount());
        assertEquals(AssetType.CHAPTER, first.assetType());
        assertEquals(2, resp.chapters().get(1).chapterNo());
    }

    @Test
    void listChaptersShouldReturnEmptyWhenProcessing() {
        String sourceId = "doc-processing";
        Media parent = processingMedia(sourceId, "redis.md", 10L, AssetType.DOCUMENT);
        when(mediaMapper.findByFileId(sourceId)).thenReturn(parent);
        when(mediaMapper.findChaptersByParentFileId(sourceId)).thenReturn(List.of());

        ChaptersResponse resp = mediaService.listChapters(sourceId);

        assertEquals(sourceId, resp.sourceFileId());
        assertTrue(resp.chapters().isEmpty());
    }

    @Test
    void listChaptersShould404WhenParentMissing() {
        when(mediaMapper.findByFileId("missing")).thenReturn(null);
        assertThrows(ResponseStatusException.class, () -> mediaService.listChapters("missing"));
    }

    private Media uploadingMedia(String fileId, AssetType assetType) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setAssetType(assetType);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setStatus(MediaStatus.UPLOADING);
        return media;
    }

    private Media processingMedia(String fileId, String filename, long size, AssetType assetType) {
        Media media = new Media();
        media.setId(1L);
        media.setFileId(fileId);
        media.setAssetType(assetType);
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
        media.setAssetType(AssetType.VIDEO);
        media.setObjectKey("raw/" + fileId + "/source.mp4");
        media.setFilename(filename);
        media.setSize(size);
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }

    private Media finishedImage(String fileId) {
        Media media = new Media();
        media.setId(3L);
        media.setFileId(fileId);
        media.setAssetType(AssetType.IMAGE);
        media.setObjectKey(ObjectKeys.raw(fileId, "jpg"));
        media.setFilename("cover.jpg");
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }

    private Media finishedDocument(String fileId) {
        Media media = new Media();
        media.setId(10L);
        media.setFileId(fileId);
        media.setAssetType(AssetType.DOCUMENT);
        media.setObjectKey("raw/" + fileId + "/source.bin");
        media.setFilename("redis.md");
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }

    private Media chapterMedia(String parentFileId, int chapterNo, String fileId,
                               String title, int wordCount) {
        Media media = new Media();
        media.setId(100L + chapterNo);
        media.setFileId(fileId);
        media.setAssetType(AssetType.CHAPTER);
        media.setObjectKey(ObjectKeys.chapter(parentFileId, chapterNo));
        media.setFilename(title);
        media.setParentFileId(parentFileId);
        media.setChapterNo(chapterNo);
        media.setPageCount(wordCount);
        media.setStatus(MediaStatus.FINISHED);
        return media;
    }
}
