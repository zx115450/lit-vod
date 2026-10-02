package com.example.vod.worker.consumer;

import com.example.vod.common.IdGenerator;
import com.example.vod.common.document.ChapterPiece;
import com.example.vod.common.document.ChapterSplitter;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.common.messaging.DocumentSplitTaskMessage;
import com.example.vod.common.messaging.RabbitConfig;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.worker.callback.CallbackNotifier;
import com.example.vod.worker.callback.CallbackPayload;
import com.example.vod.worker.config.WorkerProperties;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * DOCUMENT 切章消费者：按 {@link SplitRule} 切章，每章一个 MinIO 对象 + 一条 CHAPTER media。
 *
 * <p>独立队列 {@code vod.document.split}，并发 1，不调用 FFmpeg。
 * 切不出至少 1 章或中途失败 → 父 FAILED，清理已写 chap 前缀与 CHAPTER 行。
 */
@Slf4j
@Component
public class DocumentSplitConsumer {

    private static final int ERROR_MSG_MAX = 512;
    private static final int FILENAME_MAX = 255;

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final MinioStorage minioStorage;
    private final WorkerProperties props;
    private final CallbackNotifier callbackNotifier;

    public DocumentSplitConsumer(MediaMapper mediaMapper,
                                 MediaTaskMapper mediaTaskMapper,
                                 MinioStorage minioStorage,
                                 WorkerProperties props,
                                 CallbackNotifier callbackNotifier) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.props = props;
        this.callbackNotifier = callbackNotifier;
    }

    @RabbitListener(queues = RabbitConfig.DOCUMENT_SPLIT_QUEUE, concurrency = "1")
    public void onMessage(@Payload DocumentSplitTaskMessage message,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          Channel channel) throws IOException {
        String fileId = message.fileId();
        Long taskId = message.taskId();
        SplitRule rule = message.splitRuleOrDefault();
        log.info("received document split fileId={} taskId={} splitRule={}", fileId, taskId, rule);

        Path workDir = Path.of(props.tempDir(), "split-" + fileId);
        try {
            MediaTask task = mediaTaskMapper.findById(taskId);
            if (task == null) {
                log.warn("split task not found, ack and drop: taskId={}", taskId);
                ack(channel, deliveryTag);
                return;
            }

            int attempt = (task.getAttempt() == null ? 0 : task.getAttempt()) + 1;
            mediaTaskMapper.updateRunning(taskId, MediaTaskStatus.RUNNING, attempt);

            Files.createDirectories(workDir);
            Path sourceFile = workDir.resolve("source.bin");
            minioStorage.download(message.objectKey(), sourceFile);
            String text = Files.readString(sourceFile, StandardCharsets.UTF_8);

            List<ChapterPiece> pieces = ChapterSplitter.split(text, rule);
            if (pieces.isEmpty()) {
                String msg = "no chapters found for splitRule=" + rule;
                log.warn("document split empty fileId={} {}", fileId, msg);
                cleanupPartial(fileId);
                mediaMapper.updateFailed(fileId, MediaStatus.FAILED, truncate(msg));
                mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.FAILED, truncate(msg));
                callbackNotifier.notifyAsync(CallbackPayload.documentFailed(fileId, truncate(msg)));
                ack(channel, deliveryTag);
                return;
            }

            // 重投递 / 重跑：先清旧 chap 与 CHAPTER 行再写
            cleanupPartial(fileId);

            for (ChapterPiece piece : pieces) {
                writeChapter(fileId, piece, workDir);
            }

            mediaMapper.updateStatus(fileId, MediaStatus.FINISHED);
            mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.SUCCESS, null);
            log.info("document split finished fileId={} taskId={} chapters={}",
                    fileId, taskId, pieces.size());
            callbackNotifier.notifyAsync(CallbackPayload.documentProcessed(fileId));
            ack(channel, deliveryTag);
        } catch (Exception ex) {
            log.error("document split failed fileId={} taskId={}", fileId, taskId, ex);
            String err = truncate(ex.getMessage());
            try {
                cleanupPartial(fileId);
                mediaMapper.updateFailed(fileId, MediaStatus.FAILED, err);
                if (taskId != null) {
                    mediaTaskMapper.updateFinished(taskId, MediaTaskStatus.FAILED, err);
                }
            } catch (Exception ignore) {
                // best-effort
            }
            callbackNotifier.notifyAsync(CallbackPayload.documentFailed(fileId, err));
            ack(channel, deliveryTag);
        } finally {
            cleanupWorkDir(workDir);
        }
    }

    private void writeChapter(String sourceFileId, ChapterPiece piece, Path workDir) throws IOException {
        String chapterFileId = IdGenerator.fileId();
        String objectKey = ObjectKeys.chapter(sourceFileId, piece.chapterNo());
        Path local = workDir.resolve(String.format("%03d.md", piece.chapterNo()));
        Files.writeString(local, piece.content(), StandardCharsets.UTF_8);
        minioStorage.uploadFile(objectKey, local, "text/markdown; charset=utf-8");

        long byteSize = Files.size(local);
        Media chapter = new Media();
        chapter.setFileId(chapterFileId);
        chapter.setAssetType(AssetType.CHAPTER);
        chapter.setObjectKey(objectKey);
        chapter.setFilename(truncateFilename(piece.title()));
        chapter.setMimeType(AssetType.CHAPTER.defaultMimeType());
        chapter.setParentFileId(sourceFileId);
        chapter.setChapterNo(piece.chapterNo());
        // MVP：page_count 复用为 wordCount（CHAPTER 无页数语义）
        chapter.setPageCount(piece.wordCount());
        chapter.setSize(byteSize);
        chapter.setStatus(MediaStatus.FINISHED);
        mediaMapper.insert(chapter);
        log.info("chapter written source={} chapterNo={} fileId={} wordCount={}",
                sourceFileId, piece.chapterNo(), chapterFileId, piece.wordCount());
    }

    private void cleanupPartial(String sourceFileId) {
        try {
            minioStorage.removePrefix(ObjectKeys.chapterPrefix(sourceFileId));
        } catch (Exception e) {
            log.warn("cleanup chap prefix failed source={}: {}", sourceFileId, e.toString());
        }
        try {
            mediaMapper.deleteByParentFileId(sourceFileId);
        } catch (Exception e) {
            log.warn("cleanup CHAPTER rows failed source={}: {}", sourceFileId, e.toString());
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
            return "document split failed";
        }
        return msg.length() <= ERROR_MSG_MAX ? msg : msg.substring(0, ERROR_MSG_MAX);
    }

    private static String truncateFilename(String title) {
        if (title == null || title.isBlank()) {
            return "chapter.md";
        }
        String name = title.length() <= FILENAME_MAX ? title : title.substring(0, FILENAME_MAX);
        return name;
    }
}
