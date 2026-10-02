package com.example.vod.worker.consumer;

import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.common.messaging.DocumentSplitTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.worker.callback.CallbackNotifier;
import com.example.vod.worker.callback.CallbackPayload;
import com.example.vod.worker.config.WorkerProperties;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentSplitConsumerTest {

    @TempDir
    Path tempDir;

    private MediaMapper mediaMapper;
    private MediaTaskMapper mediaTaskMapper;
    private MinioStorage minioStorage;
    private CallbackNotifier callbackNotifier;
    private DocumentSplitConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        mediaTaskMapper = mock(MediaTaskMapper.class);
        minioStorage = mock(MinioStorage.class);
        callbackNotifier = mock(CallbackNotifier.class);
        channel = mock(Channel.class);
        WorkerProperties props = new WorkerProperties(tempDir.toString(), 3, 21600, 1);
        consumer = new DocumentSplitConsumer(mediaMapper, mediaTaskMapper, minioStorage, props, callbackNotifier);
    }

    @Test
    void shouldWriteChaptersAndFinishParent() throws Exception {
        String sourceId = "doc-ok";
        Long taskId = 9L;
        MediaTask task = pendingTask(taskId, sourceId);
        when(mediaTaskMapper.findById(taskId)).thenReturn(task);

        String md = """
                ## 持久化
                a
                ## 复制
                b
                ## 集群
                c
                """;
        stubDownload(ObjectKeys.raw(sourceId, "bin"), md);

        DocumentSplitTaskMessage message = new DocumentSplitTaskMessage(
                sourceId, 1L, ObjectKeys.raw(sourceId, "bin"), taskId, SplitRule.MARKDOWN);

        consumer.onMessage(message, 1L, channel);

        ArgumentCaptor<Media> mediaCaptor = ArgumentCaptor.forClass(Media.class);
        verify(mediaMapper, times(3)).insert(mediaCaptor.capture());
        List<Media> chapters = mediaCaptor.getAllValues();
        assertEquals(1, chapters.get(0).getChapterNo());
        assertEquals("持久化", chapters.get(0).getFilename());
        assertEquals(ObjectKeys.chapter(sourceId, 1), chapters.get(0).getObjectKey());
        assertEquals(sourceId, chapters.get(0).getParentFileId());
        assertTrue(chapters.get(0).getPageCount() > 0);

        verify(minioStorage).uploadFile(eq(ObjectKeys.chapter(sourceId, 1)), any(Path.class), anyString());
        verify(minioStorage).uploadFile(eq(ObjectKeys.chapter(sourceId, 2)), any(Path.class), anyString());
        verify(minioStorage).uploadFile(eq(ObjectKeys.chapter(sourceId, 3)), any(Path.class), anyString());
        verify(mediaMapper).updateStatus(sourceId, MediaStatus.FINISHED);
        verify(mediaTaskMapper).updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
        verify(callbackNotifier).notifyAsync(any(CallbackPayload.class));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldFailWithoutInsertingWhenNoHeading() throws Exception {
        String sourceId = "doc-empty";
        Long taskId = 11L;
        when(mediaTaskMapper.findById(taskId)).thenReturn(pendingTask(taskId, sourceId));
        stubDownload(ObjectKeys.raw(sourceId, "bin"), "没有标题的正文");

        DocumentSplitTaskMessage message = new DocumentSplitTaskMessage(
                sourceId, 1L, ObjectKeys.raw(sourceId, "bin"), taskId, SplitRule.MARKDOWN);

        consumer.onMessage(message, 2L, channel);

        verify(mediaMapper, never()).insert(any());
        verify(mediaMapper).updateFailed(eq(sourceId), eq(MediaStatus.FAILED), anyString());
        verify(mediaTaskMapper).updateFinished(eq(taskId), eq(MediaTaskStatus.FAILED), anyString());
        verify(minioStorage).removePrefix(ObjectKeys.chapterPrefix(sourceId));
        verify(mediaMapper).deleteByParentFileId(sourceId);
        verify(callbackNotifier).notifyAsync(any(CallbackPayload.class));
        verify(channel).basicAck(2L, false);
    }

    private static MediaTask pendingTask(Long taskId, String fileId) {
        MediaTask task = new MediaTask();
        task.setId(taskId);
        task.setFileId(fileId);
        task.setAttempt(0);
        return task;
    }

    private void stubDownload(String objectKey, String content) {
        doAnswer(invocation -> {
            Path local = invocation.getArgument(1);
            Files.createDirectories(local.getParent());
            Files.writeString(local, content, StandardCharsets.UTF_8);
            return null;
        }).when(minioStorage).download(eq(objectKey), any(Path.class));
    }
}
