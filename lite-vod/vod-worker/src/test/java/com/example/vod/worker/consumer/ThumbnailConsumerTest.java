package com.example.vod.worker.consumer;

import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.messaging.ThumbnailTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.worker.config.WorkerProperties;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThumbnailConsumerTest {

    @TempDir
    Path tempDir;

    private MediaMapper mediaMapper;
    private MediaTaskMapper mediaTaskMapper;
    private MinioStorage minioStorage;
    private ThumbnailConsumer consumer;
    private Channel channel;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        mediaTaskMapper = mock(MediaTaskMapper.class);
        minioStorage = mock(MinioStorage.class);
        channel = mock(Channel.class);
        WorkerProperties props = new WorkerProperties(tempDir.toString(), 3, 21600, 1);
        consumer = new ThumbnailConsumer(mediaMapper, mediaTaskMapper, minioStorage, props);
    }

    @Test
    void shouldUploadCoverAndFinishWithoutHls() throws Exception {
        String fileId = "img-ok";
        Long taskId = 4L;
        String sourceKey = ObjectKeys.raw(fileId, "jpg");
        when(mediaTaskMapper.findById(taskId)).thenReturn(pendingTask(taskId, fileId));
        stubPngDownload(sourceKey);

        consumer.onMessage(new ThumbnailTaskMessage(fileId, 1L, sourceKey, taskId), 1L, channel);

        String coverKey = ObjectKeys.imageCover(fileId);
        verify(minioStorage).uploadFile(eq(coverKey), any(Path.class), eq("image/jpeg"));
        verify(mediaMapper).updateThumbnailResult(fileId, MediaStatus.FINISHED, coverKey, coverKey, null);
        verify(mediaTaskMapper).updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
        verify(mediaMapper, never()).updateProcessed(anyString(), any(), anyString(), anyString(), anyFloat());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void shouldKeepOriginalWhenDecodeFails() throws Exception {
        String fileId = "img-bad";
        Long taskId = 5L;
        String sourceKey = ObjectKeys.raw(fileId, "jpg");
        when(mediaTaskMapper.findById(taskId)).thenReturn(pendingTask(taskId, fileId));
        doAnswer(invocation -> {
            Path local = invocation.getArgument(1);
            Files.createDirectories(local.getParent());
            Files.writeString(local, "not-an-image");
            return null;
        }).when(minioStorage).download(eq(sourceKey), any(Path.class));

        consumer.onMessage(new ThumbnailTaskMessage(fileId, 1L, sourceKey, taskId), 2L, channel);

        verify(minioStorage, never()).uploadFile(anyString(), any(Path.class), anyString());
        verify(mediaMapper).updateThumbnailResult(
                eq(fileId), eq(MediaStatus.FINISHED), eq(sourceKey), isNull(), anyString());
        verify(mediaTaskMapper).updateFinished(eq(taskId), eq(MediaTaskStatus.SUCCESS), anyString());
        verify(channel).basicAck(2L, false);
    }

    private static MediaTask pendingTask(Long taskId, String fileId) {
        MediaTask task = new MediaTask();
        task.setId(taskId);
        task.setFileId(fileId);
        task.setAttempt(0);
        return task;
    }

    private void stubPngDownload(String objectKey) {
        doAnswer(invocation -> {
            Path local = invocation.getArgument(1);
            Files.createDirectories(local.getParent());
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 16, 16);
            graphics.dispose();
            ImageIO.write(image, "png", local.toFile());
            return null;
        }).when(minioStorage).download(eq(objectKey), any(Path.class));
    }
}
