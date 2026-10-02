package com.example.vod.worker.consumer;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.ProcedureDeadLetter;
import com.example.vod.common.domain.media.ProcedureDeadLetterMapper;
import com.example.vod.common.messaging.ProcedureRetry;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.worker.callback.CallbackNotifier;
import com.example.vod.worker.callback.CallbackPayload;
import com.example.vod.worker.config.WorkerProperties;
import com.example.vod.worker.ffmpeg.FfmpegService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcedureConsumerRetryTest {

    private MediaMapper mediaMapper;
    private MediaTaskMapper mediaTaskMapper;
    private ProcedureDeadLetterMapper deadLetterMapper;
    private MinioStorage minioStorage;
    private FfmpegService ffmpegService;
    private CallbackNotifier callbackNotifier;
    private RabbitTemplate rabbitTemplate;
    private Channel channel;
    private ProcedureConsumer consumer;

    @BeforeEach
    void setUp() {
        mediaMapper = mock(MediaMapper.class);
        mediaTaskMapper = mock(MediaTaskMapper.class);
        deadLetterMapper = mock(ProcedureDeadLetterMapper.class);
        minioStorage = mock(MinioStorage.class);
        ffmpegService = mock(FfmpegService.class);
        callbackNotifier = mock(CallbackNotifier.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        channel = mock(Channel.class);
        consumer = new ProcedureConsumer(
                mediaMapper,
                mediaTaskMapper,
                deadLetterMapper,
                minioStorage,
                ffmpegService,
                new WorkerProperties(System.getProperty("java.io.tmpdir"), 3, 21_600, 1),
                new AbrProperties(false, false, null, null, null, null),
                new PreviewProperties(false, 30, 1800),
                callbackNotifier,
                rabbitTemplate);
    }

    @Test
    void firstFailureWaitsFiveSecondsThenAcks() throws Exception {
        MediaTask task = new MediaTask();
        task.setAttempt(0);
        when(mediaTaskMapper.findById(9L)).thenReturn(task);
        doThrow(new RuntimeException("download failed")).when(minioStorage).download(eq("raw/a.mp4"), any(Path.class));

        ProcedureTaskMessage message = new ProcedureTaskMessage("f1", 1L, "raw/a.mp4", 9L);
        consumer.onMessage(message, 7L, channel);

        verify(rabbitTemplate).convertAndSend(
                ProcedureRetry.EXCHANGE_NAME,
                ProcedureRetry.routingKey(1),
                message);
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        verify(callbackNotifier, never()).notifyAsync(any(CallbackPayload.class));
        verify(deadLetterMapper, never()).upsert(any());
    }

    @Test
    void secondFailureWaitsTenSeconds() throws Exception {
        MediaTask task = new MediaTask();
        task.setAttempt(1);
        when(mediaTaskMapper.findById(9L)).thenReturn(task);
        doThrow(new RuntimeException("download failed")).when(minioStorage).download(eq("raw/a.mp4"), any(Path.class));

        ProcedureTaskMessage message = new ProcedureTaskMessage("f1", 1L, "raw/a.mp4", 9L);
        consumer.onMessage(message, 7L, channel);

        verify(rabbitTemplate).convertAndSend(
                ProcedureRetry.EXCHANGE_NAME,
                ProcedureRetry.routingKey(2),
                message);
        verify(channel).basicAck(7L, false);
        verify(callbackNotifier, never()).notifyAsync(any(CallbackPayload.class));
    }

    @Test
    void exhaustedAttemptAcksWithoutDelay() throws Exception {
        MediaTask task = new MediaTask();
        task.setAttempt(2);
        when(mediaTaskMapper.findById(9L)).thenReturn(task);
        doThrow(new RuntimeException("download failed")).when(minioStorage).download(eq("raw/a.mp4"), any(Path.class));

        ProcedureTaskMessage message = new ProcedureTaskMessage("f1", 1L, "raw/a.mp4", 9L);
        consumer.onMessage(message, 7L, channel);

        verify(rabbitTemplate, never()).convertAndSend(
                eq(ProcedureRetry.EXCHANGE_NAME), anyString(), any(ProcedureTaskMessage.class));
        verify(rabbitTemplate).convertAndSend(
                ProcedureRetry.DEAD_EXCHANGE, ProcedureRetry.DLQ_ROUTING_KEY, message);
        ArgumentCaptor<ProcedureDeadLetter> parked = ArgumentCaptor.forClass(ProcedureDeadLetter.class);
        verify(deadLetterMapper).upsert(parked.capture());
        Assertions.assertEquals(9L, parked.getValue().getTaskId());
        Assertions.assertEquals(3, parked.getValue().getAttempt());
        Assertions.assertEquals("FULL", parked.getValue().getTaskType());
        verify(channel).basicAck(7L, false);
        verify(callbackNotifier).notifyAsync(any(CallbackPayload.class));
    }

    @Test
    void durationOverLimitParksWithoutRetry() throws Exception {
        MediaTask task = new MediaTask();
        task.setAttempt(0);
        when(mediaTaskMapper.findById(9L)).thenReturn(task);
        when(ffmpegService.probeDuration(any())).thenReturn(100_000d);

        ProcedureTaskMessage message = new ProcedureTaskMessage("f1", 1L, "raw/a.mp4", 9L);
        consumer.onMessage(message, 7L, channel);

        verify(rabbitTemplate, never()).convertAndSend(
                eq(ProcedureRetry.EXCHANGE_NAME), anyString(), any(ProcedureTaskMessage.class));
        verify(rabbitTemplate).convertAndSend(
                ProcedureRetry.DEAD_EXCHANGE, ProcedureRetry.DLQ_ROUTING_KEY, message);
        verify(deadLetterMapper).upsert(any(ProcedureDeadLetter.class));
        verify(channel).basicAck(7L, false);
    }
}
