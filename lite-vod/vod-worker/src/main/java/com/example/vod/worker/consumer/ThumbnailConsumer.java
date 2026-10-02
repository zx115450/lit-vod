package com.example.vod.worker.consumer;

import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.messaging.RabbitConfig;
import com.example.vod.common.messaging.ThumbnailTaskMessage;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.worker.config.WorkerProperties;
import com.example.vod.worker.image.ThumbnailEncoder;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * IMAGE 缩略图消费者：生成 {@code img/{fileId}/cover.jpg}。
 *
 * <p>独立队列 {@code vod.image.thumbnail}，并发 1，不调用 FFmpeg。
 * 压缩失败仍把媒资标 {@link MediaStatus#FINISHED}，{@code object_key} 保持原图，不写 HLS。
 */
@Slf4j
@Component
public class ThumbnailConsumer {

    private static final int ERROR_MSG_MAX = 512;

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final MinioStorage minioStorage;
    private final WorkerProperties props;

    public ThumbnailConsumer(MediaMapper mediaMapper,
                             MediaTaskMapper mediaTaskMapper,
                             MinioStorage minioStorage,
                             WorkerProperties props) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.props = props;
    }

    @RabbitListener(queues = RabbitConfig.IMAGE_THUMBNAIL_QUEUE, concurrency = "1")
    public void onMessage(@Payload ThumbnailTaskMessage message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          Channel channel) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();
        log.info("received thumbnail fileId={} taskId={}", fileId, taskId);

        Path workDir = Path.of(props.tempDir(), "thumb-" + fileId);
        try {
            MediaTask task = mediaTaskMapper.findById(taskId);
            if (task == null) {
                log.warn("thumbnail task not found, ack and drop: taskId={}", taskId);
                ack(channel, deliveryTag);
                return;
            }

            int attempt = (task.getAttempt() == null ? 0 : task.getAttempt()) + 1;
            mediaTaskMapper.updateRunning(taskId, MediaTaskStatus.RUNNING, attempt);

            try {
                Files.createDirectories(workDir);
                Path sourceFile = workDir.resolve("source.bin");
                Path coverFile = workDir.resolve("cover.jpg");
                minioStorage.download(message.objectKey(), sourceFile);
                ThumbnailEncoder.writeCoverJpeg(sourceFile, coverFile);
                //img/{fileId}/cover.jpg
                String coverKey = ObjectKeys.imageCover(fileId);
                minioStorage.uploadFile(coverKey, coverFile, "image/jpeg");
                mediaMapper.updateThumbnailResult(fileId, MediaStatus.FINISHED, coverKey, coverKey, null);
                mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
                log.info("thumbnail finished fileId={} taskId={} cover={}", fileId, taskId, coverKey);
            } catch (Exception thumbEx) {
                String note = truncate("thumbnail skipped: " + thumbEx.getMessage());
                log.warn("thumbnail skipped, keep original fileId={} taskId={}: {}", fileId, taskId, note);
                mediaMapper.updateThumbnailResult(
                        fileId, MediaStatus.FINISHED, message.objectKey(), null, note);
                mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, note);
            }
            ack(channel, deliveryTag);
        } catch (Exception ex) {
            log.error("thumbnail worker failed fileId={} taskId={}", fileId, taskId, ex);
            try {
                mediaMapper.updateThumbnailResult(
                        fileId, MediaStatus.FINISHED, message.objectKey(), null,
                        truncate("thumbnail skipped: " + ex.getMessage()));
                if (taskId != null) {
                    mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS,
                            truncate("thumbnail skipped: " + ex.getMessage()));
                }
            } catch (Exception ignore) {
                // best-effort：媒资仍应可按原图访问
            }
            ack(channel, deliveryTag);
        } finally {
            cleanupWorkDir(workDir);
        }
    }

    private static void cleanupWorkDir(Path workDir) {
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

    private static void ack(Channel channel, long deliveryTag) throws IOException {
        channel.basicAck(deliveryTag, false);
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return "thumbnail skipped";
        }
        return msg.length() <= ERROR_MSG_MAX ? msg : msg.substring(0, ERROR_MSG_MAX);
    }
}
