package com.example.vod.worker.consumer;

import com.example.vod.worker.callback.CallbackNotifier;
import com.example.vod.worker.callback.CallbackPayload;
import com.example.vod.worker.config.WorkerProperties;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.worker.ffmpeg.FfmpegService;
import com.example.vod.worker.ffmpeg.TranscodeException;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.worker.process.CommandTimeoutException;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 转码任务消费者，对应步骤 09 消费逻辑：
 *
 * <pre>
 * 1. 收到 {fileId, objectKey, taskId}
 * 2. 更新 task=RUNNING，attempt+1
 * 3. 下载到 {tempDir}/{fileId}/source.mp4
 * 4. FFmpeg：HLS + 封面
 * 5. 上传 hls/{fileId}/* 与 cover/{fileId}.jpg
 * 6. ffprobe 取 duration，更新 media=PROCESSED，写入 media_url、cover_url
 * 7. task=SUCCESS，finished_at
 * 8. 异步 Webhook（VOD_CALLBACK_URL），不阻塞清理
 * 9. 删除本地临时目录
 * </pre>
 *
 * <p>失败处理：
 * <ul>
 *   <li>media.status=FAILED，error_msg 截断到 512</li>
 *   <li>task.status=FAILED</li>
 *   <li>attempt &lt; maxAttempts 时 requeue 让 MQ 重试；超过则 ack 停止（毒消息保护）并 Webhook FAILED</li>
 *   <li>超限（时长 &gt; 6 小时）直接 FAILED 且不重试，Webhook FAILED</li>
 * </ul>
 *
 * <p>临时目录在 finally 中无条件清理，避免磁盘泄漏。
 */
@Slf4j
@Component
public class ProcedureConsumer {

    private static final int ERROR_MSG_MAX = 512;
    private static final String COVER_START_TIME_SHORT = "00:00:00";
    private static final String COVER_START_TIME_NORMAL = "00:00:03";
    private static final double SHORT_THRESHOLD_SEC = 3.0;

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final MinioStorage minioStorage;
    private final FfmpegService ffmpegService;
    private final WorkerProperties props;
    private final CallbackNotifier callbackNotifier;

    public ProcedureConsumer(MediaMapper mediaMapper,
                             MediaTaskMapper mediaTaskMapper,
                             MinioStorage minioStorage,
                             FfmpegService ffmpegService,
                             WorkerProperties props,
                             CallbackNotifier callbackNotifier) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.ffmpegService = ffmpegService;
        this.props = props;
        this.callbackNotifier = callbackNotifier;
    }

    @RabbitListener(queues = "${worker.queue-name:vod.procedure}", concurrency = "${worker.concurrency:1}")
    public void onMessage(@Payload ProcedureTaskMessage message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          Channel channel) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();
        log.info("received task fileId={} taskId={} objectKey={}", fileId, taskId, message.objectKey());

        Path workDir = Path.of(props.tempDir(), fileId);
        // attempt 在 try 内更新；catch 里若 attempt 还是 0 表示任务行没读到 / RUNNING 没更新成功，按首次失败处理
        int attempt = 0;
        try {
            // 1. 校验任务行存在；防止已删任务被重投
            MediaTask task = mediaTaskMapper.findById(taskId);
            if (task == null) {
                log.warn("task not found, ack and drop: taskId={}", taskId);
                ack(channel, deliveryTag);
                return;
            }

            // 2. RUNNING + attempt+1
            attempt = (task.getAttempt() == null ? 0 : task.getAttempt()) + 1;
            mediaTaskMapper.updateRunning(taskId, MediaTaskStatus.RUNNING, attempt);
            log.info("task running taskId={} attempt={}", taskId, attempt);

            // 3. 下载原片
            Files.createDirectories(workDir);
            minioStorage.download(message.objectKey(), workDir.resolve("source.mp4"));

            // 4. 先探时长，用于：判定超限 + 决定封面起始时间
            double duration = ffmpegService.probeDuration(workDir);
            if (duration > props.maxDurationSec()) {
                String msg = String.format("duration %.0fs exceeds limit %ds", duration, props.maxDurationSec());
                log.warn("duration over limit, mark FAILED without retry: fileId={} {}", fileId, msg);
                failTaskAndMedia(taskId, fileId, msg);
                // 终态失败才回调，避免重试中间态把业务打成 FAILED
                callbackNotifier.notifyAsync(CallbackPayload.failed(fileId, msg));
                ack(channel, deliveryTag);
                return;
            }

            // 5. 转码 HLS
            ffmpegService.transcodeHls(workDir);

            // 6. 截封面（片长短于 3 秒用 0 秒）
            String startTime = duration < SHORT_THRESHOLD_SEC ? COVER_START_TIME_SHORT : COVER_START_TIME_NORMAL;
            ffmpegService.captureCover(workDir, startTime);

            // 7. 上传 HLS + 封面（先上传再改库，避免库已 PROCESSED 但桶里缺切片）
            uploadTranscodeOutputs(fileId, workDir);

            // 8. 写回 media = PROCESSED
            String mediaUrl = ObjectKeys.hlsPlaylist(fileId);
            String coverUrl = ObjectKeys.cover(fileId);
            mediaMapper.updateProcessed(fileId, MediaStatus.FINISHED, mediaUrl, coverUrl, (float) duration);

            // 9. task = SUCCESS
            mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
            log.info("task success fileId={} taskId={} duration={}s", fileId, taskId, duration);

            // 10. Webhook（异步，不阻塞 finally 清临时目录；失败不影响已 PROCESSED）
            callbackNotifier.notifyAsync(CallbackPayload.processed(fileId, coverUrl, duration));

            ack(channel, deliveryTag);
        } catch (Exception e) {
            handleFailure(taskId, fileId, attempt, e, channel, deliveryTag);
        } finally {
            cleanup(workDir);
        }
    }

    /**
     * 上传 index.m3u8 + 全部 segment_*.ts + cover.jpg。
     * 任一上传失败抛异常，整任务 FAILED，不会出现「仅清单成功却标 PROCESSED」。
     */
    private void uploadTranscodeOutputs(String fileId, Path workDir) throws IOException {
        Path playlist = workDir.resolve("index.m3u8");
        Path cover = workDir.resolve("cover.jpg");
        if (!Files.isRegularFile(playlist)) {
            throw new IllegalStateException("missing index.m3u8 after transcode");
        }
        if (!Files.isRegularFile(cover)) {
            throw new IllegalStateException("missing cover.jpg after transcode");
        }

        // 先上传切片，再上传清单，避免播放器拿到清单却拉不到 ts
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workDir, "segment_*.ts")) {
            for (Path segment : stream) {
                String name = segment.getFileName().toString();
                // segment_012.ts → 下标 12，与 ObjectKeys.hlsSegment 的 %03d 对齐
                int index = Integer.parseInt(name.substring("segment_".length(), name.length() - ".ts".length()));
                minioStorage.uploadFile(ObjectKeys.hlsSegment(fileId, index), segment, "video/MP2T");
            }
        }
        minioStorage.uploadFile(ObjectKeys.hlsPlaylist(fileId), playlist, "application/vnd.apple.mpegurl");
        minioStorage.uploadFile(ObjectKeys.cover(fileId), cover, "image/jpeg");
    }

    /**
     * 失败统一处理：写 FAILED + error_msg；attempt &lt; maxAttempts 时 requeue 重试，否则 ack 停止。
     */
    private void handleFailure(Long taskId, String fileId, int attempt, Throwable cause,
                               Channel channel, long deliveryTag) throws IOException {
        String errorMsg = summarize(cause);
        log.error("task failed fileId={} taskId={} attempt={}/{}: {}",
                fileId, taskId, attempt, props.maxAttempts(), errorMsg, cause);

        failTaskAndMedia(taskId, fileId, errorMsg);

        // attempt == 0 表示任务行没读到 / RUNNING 没更新成功，按首次失败处理，允许重试
        int effectiveAttempt = Math.max(attempt, 1);
        if (effectiveAttempt < props.maxAttempts()) {
            // requeue 让 MQ 重投，触发下一次 attempt；中间失败不发 Webhook
            log.info("requeue for retry fileId={} taskId={}", fileId, taskId);
            channel.basicReject(deliveryTag, true);
        } else {
            log.warn("attempt exceeded, ack and stop (poison message): fileId={} taskId={}", fileId, taskId);
            callbackNotifier.notifyAsync(CallbackPayload.failed(fileId, errorMsg));
            ack(channel, deliveryTag);
        }
    }

    private void failTaskAndMedia(Long taskId, String fileId, String rawErrorMsg) {
        String errorMsg = CommandTimeoutException.truncate(rawErrorMsg, ERROR_MSG_MAX);
        mediaMapper.updateFailed(fileId, MediaStatus.FAILED, errorMsg);
        mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.FAILED, errorMsg);
    }

    /**
     * 把异常摘要成单行 error_msg：优先用 TranscodeException / CommandTimeoutException 自带的截断输出。
     */
    private static String summarize(Throwable e) {
        if (e instanceof TranscodeException te && te.truncatedOutput() != null && !te.truncatedOutput().isBlank()) {
            return te.getMessage() + " | " + te.truncatedOutput();
        }
        if (e instanceof CommandTimeoutException ce && ce.truncatedOutput() != null && !ce.truncatedOutput().isBlank()) {
            return ce.getMessage() + " | " + ce.truncatedOutput();
        }
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static void ack(Channel channel, long deliveryTag) throws IOException {
        channel.basicAck(deliveryTag, false);
    }

    /**
     * 递归删除工作目录，含成功与失败两条路径，避免磁盘泄漏。
     */
    private static void cleanup(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                            // 删除失败不影响主流程，下次启动可由外部清理
                        }
                    });
        } catch (IOException e) {
            log.warn("cleanup workDir failed: {}", workDir, e);
        }
    }
}
