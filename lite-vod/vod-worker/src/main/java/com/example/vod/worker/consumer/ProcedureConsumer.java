package com.example.vod.worker.consumer;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.worker.callback.CallbackNotifier;
import com.example.vod.worker.callback.CallbackPayload;
import com.example.vod.worker.config.WorkerProperties;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.LadderStatus;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.worker.ffmpeg.FfmpegService;
import com.example.vod.worker.ffmpeg.PreviewPlaylistBuilder;
import com.example.vod.worker.ffmpeg.TranscodeException;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.worker.process.CommandTimeoutException;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 转码任务消费者，对应步骤 09 消费逻辑。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>一期 {@code FULL}：下载 → 截封面 → 一次出齐 HLS（单档或 ABR）→ 上传 → 标 FINISHED。</li>
 *   <li>二期渐进式 {@code FAST/LADDER}：快档先出可播并标 PLAYABLE，Worker 再自投递 LADDER 补档，
 *       全部补完后标 FINISHED。补档失败不把已可播媒资打成 FAILED。</li>
 * </ul>
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
    private final AbrProperties abrProperties;
    private final PreviewProperties previewProperties;
    private final CallbackNotifier callbackNotifier;
    private final RabbitTemplate rabbitTemplate;

    public ProcedureConsumer(MediaMapper mediaMapper,
                             MediaTaskMapper mediaTaskMapper,
                             MinioStorage minioStorage,
                             FfmpegService ffmpegService,
                             WorkerProperties props,
                             AbrProperties abrProperties,
                             PreviewProperties previewProperties,
                             CallbackNotifier callbackNotifier,
                             RabbitTemplate rabbitTemplate) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.ffmpegService = ffmpegService;
        this.props = props;
        this.abrProperties = abrProperties;
        this.previewProperties = previewProperties;
        this.callbackNotifier = callbackNotifier;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = "${worker.queue-name:vod.procedure}", concurrency = "${worker.concurrency:1}")
    public void onMessage(@Payload ProcedureTaskMessage message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          Channel channel) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();
        log.info("received task fileId={} taskId={} taskType={} progressive={}",
                fileId, taskId, message.taskType(), message.isProgressive());

        Path workDir = Path.of(props.tempDir(), fileId);
        int attempt = 0;
        try {
            MediaTask task = mediaTaskMapper.findById(taskId);
            if (task == null) {
                log.warn("task not found, ack and drop: taskId={}", taskId);
                ack(channel, deliveryTag);
                return;
            }

            attempt = (task.getAttempt() == null ? 0 : task.getAttempt()) + 1;
            mediaTaskMapper.updateRunning(taskId, MediaTaskStatus.RUNNING, attempt);
            log.info("task running taskId={} attempt={} taskType={}", taskId, attempt, message.taskType());
            //创建工作目录 - workdir
            Files.createDirectories(workDir);
            minioStorage.download(message.objectKey(), workDir.resolve("source.mp4"));

            double duration = ffmpegService.probeDuration(workDir);
            if (duration > props.maxDurationSec()) {
                String msg = String.format("duration %.0fs exceeds limit %ds", duration, props.maxDurationSec());
                log.warn("duration over limit, mark FAILED without retry: fileId={} {}", fileId, msg);
                failTaskAndMedia(taskId, fileId, msg);
                callbackNotifier.notifyAsync(CallbackPayload.failed(fileId, msg));
                ack(channel, deliveryTag);
                return;
            }

            String startTime = duration < SHORT_THRESHOLD_SEC ? COVER_START_TIME_SHORT : COVER_START_TIME_NORMAL;

            switch (message.taskType()) {
                case FAST -> doFast(message, workDir, duration, startTime, channel, deliveryTag);
                case LADDER -> doLadder(message, workDir, duration, startTime, channel, deliveryTag);
                default -> doFull(message, workDir, duration, startTime, channel, deliveryTag);
            }
        } catch (Exception e) {
            handleFailure(taskId, fileId, attempt, e, channel, deliveryTag);
        } finally {
            cleanup(workDir);
        }
    }

    /**
     * 一期一次出齐逻辑，兼容旧消息与未开启渐进式的情况。
     */
    private void doFull(ProcedureTaskMessage message, Path workDir, double duration,
                        String startTime, Channel channel, long deliveryTag) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();

        ffmpegService.transcodeHls(workDir);
        ffmpegService.captureCover(workDir, startTime);
        uploadTranscodeOutputs(fileId, workDir, message.previewSeconds());

        String mediaUrl = ffmpegService.isAbrEnabled()
                ? ObjectKeys.hlsMaster(fileId)
                : ObjectKeys.hlsPlaylist(fileId);
        String coverUrl = ObjectKeys.cover(fileId);
        mediaMapper.updateProcessed(fileId, MediaStatus.FINISHED, mediaUrl, coverUrl, (float) duration);

        mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
        log.info("task success fileId={} taskId={} duration={}s mode=FULL", fileId, taskId, duration);
        callbackNotifier.notifyAsync(CallbackPayload.processed(fileId, coverUrl, duration));
        ack(channel, deliveryTag);
    }

    /**
     * 快路径：只出首档（默认 360p）→ 标 PLAYABLE → 投递 LADDER 消息。
     */
    private void doFast(ProcedureTaskMessage message, Path workDir, double duration,
                        String startTime, Channel channel, long deliveryTag) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();

        ffmpegService.transcodeFast(workDir);
        ffmpegService.captureCover(workDir, startTime);
        uploadFastOutputs(fileId, workDir, message.previewSeconds());

        String mediaUrl = ObjectKeys.hlsMaster(fileId);
        String coverUrl = ObjectKeys.cover(fileId);
        mediaMapper.updateProcessed(fileId, MediaStatus.PLAYABLE, mediaUrl, coverUrl, (float) duration);
        mediaMapper.updateLadderFinished(fileId, MediaStatus.PLAYABLE, coverUrl, LadderStatus.RUNNING.code());

        mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
        log.info("task success fileId={} taskId={} duration={}s mode=FAST -> PLAYABLE", fileId, taskId, duration);

        // 投递补档任务；MQ 投递失败不重试本次任务，避免无限循环
        try {
            MediaTask ladderTask = new MediaTask();
            ladderTask.setMediaId(message.mediaId());
            ladderTask.setFileId(fileId);
            ladderTask.setType(MediaTaskType.PROCEDURE);
            ladderTask.setStatus(MediaTaskStatus.PENDING);
            ladderTask.setAttempt(0);
            mediaTaskMapper.insert(ladderTask);

            ProcedureTaskMessage ladderMessage = new ProcedureTaskMessage(
                    fileId, message.mediaId(), message.objectKey(), ladderTask.getId(),
                    ProcedureTaskMessage.TaskType.LADDER, true, message.previewSeconds());
            rabbitTemplate.convertAndSend(
                    com.example.vod.common.messaging.RabbitConfig.EXCHANGE_NAME,
                    com.example.vod.common.messaging.RabbitConfig.ROUTING_KEY,
                    ladderMessage);
            log.info("ladder task dispatched fileId={} ladderTaskId={}", fileId, ladderTask.getId());
        } catch (Exception e) {
            // 补档投递失败不影响已可播；业务可监控告警后手动/定时重补
            log.error("dispatch ladder task failed fileId={}: {}", fileId, e.toString(), e);
        }

        callbackNotifier.notifyAsync(CallbackPayload.processed(fileId, coverUrl, duration));
        ack(channel, deliveryTag);
    }

    /**
     * 补档路径：出剩余档位 → 按桶内已有档位重写 master → 标 FINISHED。
     * 补档失败只把本任务标 FAILED，不污染媒资状态。
     */
    private void doLadder(ProcedureTaskMessage message, Path workDir, double duration,
                          String startTime, Channel channel, long deliveryTag) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();

        ffmpegService.transcodeLadder(workDir);
        ffmpegService.captureCover(workDir, startTime);
        uploadLadderOutputs(fileId, workDir);

        // 按桶内（含本次新上传）已有档位重写 master
        ffmpegService.writeMasterFromExisting(workDir, fileId);
        uploadMasterOnly(fileId, workDir);

        String coverUrl = ObjectKeys.cover(fileId);
        mediaMapper.updateLadderFinished(fileId, MediaStatus.FINISHED, coverUrl, LadderStatus.READY.code());

        mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
        log.info("task success fileId={} taskId={} duration={}s mode=LADDER -> FINISHED", fileId, taskId, duration);
        callbackNotifier.notifyAsync(CallbackPayload.processed(fileId, coverUrl, duration));
        ack(channel, deliveryTag);
    }

    private void uploadTranscodeOutputs(String fileId, Path workDir, Integer previewSeconds) throws IOException {
        Path cover = workDir.resolve("cover.jpg");
        if (!Files.isRegularFile(cover)) {
            throw new IllegalStateException("missing cover.jpg after transcode");
        }

        if (ffmpegService.isAbrEnabled()) {
            uploadAbrOutputs(fileId, workDir, abrProperties.variants(), previewSeconds);
        } else {
            uploadSingleOutputs(fileId, workDir, previewSeconds);
        }

        minioStorage.uploadFile(ObjectKeys.cover(fileId), cover, "image/jpeg");
    }

    private void uploadSingleOutputs(String fileId, Path workDir, Integer previewSeconds) throws IOException {
        Path playlist = workDir.resolve("index.m3u8");
        if (!Files.isRegularFile(playlist)) {
            throw new IllegalStateException("missing index.m3u8 after transcode");
        }
        uploadSegments(workDir, fileId, "");
        minioStorage.uploadFile(ObjectKeys.hlsPlaylist(fileId), playlist, "application/vnd.apple.mpegurl");
        uploadPreviewIfEnabled(fileId, playlist, null, previewSeconds);
    }

    private void uploadFastOutputs(String fileId, Path workDir, Integer previewSeconds) throws IOException {
        Path cover = workDir.resolve("cover.jpg");
        if (!Files.isRegularFile(cover)) {
            throw new IllegalStateException("missing cover.jpg after fast transcode");
        }

        AbrProperties.Variant fast = abrProperties.fastVariantConfig();
        Path variantDir = workDir.resolve(fast.label());
        Path variantPlaylist = variantDir.resolve("index.m3u8");
        if (!Files.isRegularFile(variantPlaylist)) {
            throw new IllegalStateException("missing " + fast.label() + "/index.m3u8 after fast transcode");
        }
        uploadSegments(variantDir, fileId, fast.label());
        minioStorage.uploadFile(ObjectKeys.hlsVariantPlaylist(fileId, fast.label()),
                variantPlaylist, "application/vnd.apple.mpegurl");

        // 首版 master 只含快档
        ffmpegService.writeMasterForVariants(workDir, List.of(fast));
        Path master = workDir.resolve("master.m3u8");
        if (!Files.isRegularFile(master)) {
            throw new IllegalStateException("missing master.m3u8 after fast transcode");
        }
        minioStorage.uploadFile(ObjectKeys.hlsMaster(fileId), master, "application/vnd.apple.mpegurl");

        minioStorage.uploadFile(ObjectKeys.cover(fileId), cover, "image/jpeg");
        uploadPreviewIfEnabled(fileId, variantPlaylist, fast.label(), previewSeconds);
    }

    private void uploadLadderOutputs(String fileId, Path workDir) throws IOException {
        List<AbrProperties.Variant> ladder = abrProperties.ladderVariants();
        for (AbrProperties.Variant v : ladder) {
            Path variantDir = workDir.resolve(v.label());
            Path variantPlaylist = variantDir.resolve("index.m3u8");
            if (!Files.isRegularFile(variantPlaylist)) {
                throw new IllegalStateException("missing " + v.label() + "/index.m3u8 after ladder transcode");
            }
            uploadSegments(variantDir, fileId, v.label());
            minioStorage.uploadFile(ObjectKeys.hlsVariantPlaylist(fileId, v.label()),
                    variantPlaylist, "application/vnd.apple.mpegurl");
        }
    }

    private void uploadMasterOnly(String fileId, Path workDir) throws IOException {
        Path master = workDir.resolve("master.m3u8");
        if (!Files.isRegularFile(master)) {
            throw new IllegalStateException("missing master.m3u8 after ladder");
        }
        minioStorage.uploadFile(ObjectKeys.hlsMaster(fileId), master, "application/vnd.apple.mpegurl");
    }

    private void uploadAbrOutputs(String fileId, Path workDir, List<AbrProperties.Variant> variants,
                                  Integer previewSeconds) throws IOException {
        Path master = workDir.resolve("master.m3u8");
        if (!Files.isRegularFile(master)) {
            throw new IllegalStateException("missing master.m3u8 after abr transcode");
        }

        for (AbrProperties.Variant v : variants) {
            Path variantDir = workDir.resolve(v.label());
            Path variantPlaylist = variantDir.resolve("index.m3u8");
            if (!Files.isRegularFile(variantPlaylist)) {
                throw new IllegalStateException("missing " + v.label() + "/index.m3u8 after abr transcode");
            }
            uploadSegments(variantDir, fileId, v.label());
            minioStorage.uploadFile(ObjectKeys.hlsVariantPlaylist(fileId, v.label()),
                    variantPlaylist, "application/vnd.apple.mpegurl");
        }

        minioStorage.uploadFile(ObjectKeys.hlsMaster(fileId), master, "application/vnd.apple.mpegurl");

        AbrProperties.Variant fast = abrProperties.fastVariantConfig();
        Path fastPlaylist = workDir.resolve(fast.label()).resolve("index.m3u8");
        uploadPreviewIfEnabled(fileId, fastPlaylist, fast.label(), previewSeconds);
    }

    /**
     * 试看 L2：从正片 media playlist 截前 N 秒写 preview.m3u8 并上传（复用正片 ts）。
     * N 优先用消息里的上传方秒数，缺省用配置默认；&lt;=0 跳过。
     *
     * @param uriPrefix ABR 档位目录名（如 360p）；单档传 null
     */
    private void uploadPreviewIfEnabled(String fileId, Path sourcePlaylist, String uriPrefix,
                                        Integer previewSecondsOverride) throws IOException {
        if (!previewProperties.l2Enabled()) {
            return;
        }
        int seconds = resolvePreviewSeconds(previewSecondsOverride);
        if (seconds <= 0) {
            log.info("skip preview.m3u8 fileId={} previewSeconds={}", fileId, seconds);
            return;
        }
        if (!Files.isRegularFile(sourcePlaylist)) {
            throw new IllegalStateException("missing source playlist for preview: " + sourcePlaylist);
        }
        String source = Files.readString(sourcePlaylist, StandardCharsets.UTF_8);
        String preview = PreviewPlaylistBuilder.build(source, seconds, uriPrefix);
        Path previewFile = sourcePlaylist.getParent().resolve("preview.m3u8");
        // 单档时 parent 即 workDir；ABR 时写在档位目录旁无妨，最终只上传文本
        if (uriPrefix != null && !uriPrefix.isBlank()) {
            previewFile = sourcePlaylist.getParent().getParent().resolve("preview.m3u8");
        }
        Files.writeString(previewFile, preview, StandardCharsets.UTF_8);
        minioStorage.uploadFile(ObjectKeys.hlsPreview(fileId), previewFile, "application/vnd.apple.mpegurl");
        log.info("uploaded preview.m3u8 fileId={} seconds={} prefix={}",
                fileId, seconds, uriPrefix == null ? "" : uriPrefix);
    }

    private int resolvePreviewSeconds(Integer override) {
        if (override != null) {
            return override <= 0 ? 0 : Math.min(override, previewProperties.maxSeconds());
        }
        return previewProperties.seconds();
    }

    private void uploadSegments(Path dir, String fileId, String label) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "segment_*.ts")) {
            for (Path segment : stream) {
                String name = segment.getFileName().toString();
                int index = Integer.parseInt(name.substring("segment_".length(), name.length() - ".ts".length()));
                String objectKey = label.isEmpty()
                        ? ObjectKeys.hlsSegment(fileId, index)
                        : ObjectKeys.hlsVariantSegment(fileId, label, index);
                minioStorage.uploadFile(objectKey, segment, "video/MP2T");
            }
        }
    }

    private void handleFailure(Long taskId, String fileId, int attempt, Throwable cause,
                               Channel channel, long deliveryTag) throws IOException {
        String errorMsg = summarize(cause);
        log.error("task failed fileId={} taskId={} attempt={}/{}: {}",
                fileId, taskId, attempt, props.maxAttempts(), errorMsg, cause);

        failTaskAndMedia(taskId, fileId, errorMsg);

        int effectiveAttempt = Math.max(attempt, 1);
        if (effectiveAttempt < props.maxAttempts()) {
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
                        }
                    });
        } catch (IOException e) {
            log.warn("cleanup workDir failed: {}", workDir, e);
        }
    }
}
