package com.example.vod.service;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.LadderStatus;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class MediaService {

    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final MinioStorage minioStorage;
    private final RabbitTemplate rabbitTemplate;
    private final AbrProperties abrProperties;
    private final PreviewProperties previewProperties;

    public MediaService(MediaMapper mediaMapper,
                        MediaTaskMapper mediaTaskMapper,
                        MinioStorage minioStorage,
                        RabbitTemplate rabbitTemplate,
                        AbrProperties abrProperties,
                        PreviewProperties previewProperties) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.rabbitTemplate = rabbitTemplate;
        this.abrProperties = abrProperties;
        this.previewProperties = previewProperties;
    }

    @Transactional
    public MediaDto commit(String fileId, String filename) {
        return commit(fileId, filename, null, null);
    }

    @Transactional
    public MediaDto commit(String fileId, String filename, Boolean progressiveOverride) {
        return commit(fileId, filename, progressiveOverride, null);
    }

    /**
     * @param progressiveOverride    {@code null} 用配置；非空则按请求决定渐进式 / 一次出齐
     * @param previewSecondsOverride {@code null} 用 {@code vod.preview.seconds}；{@code <=0} 不生成试看
     */
    @Transactional
    public MediaDto commit(String fileId, String filename, Boolean progressiveOverride,
                           Integer previewSecondsOverride) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        // 已可播或已完成的媒资直接幂等返回
        if (media.getStatus() == MediaStatus.FINISHED || media.getStatus() == MediaStatus.PLAYABLE) {
            return toDto(media);
        }

        if (!minioStorage.head(media.getObjectKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未找到上传对象，请先完成直传: " + media.getObjectKey());
        }

        long size = minioStorage.statSize(media.getObjectKey());
        if (size > MAX_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file size exceeds limit: " + size);
        }

        int previewSeconds = resolvePreviewSeconds(previewSecondsOverride);

        // 已有未完结任务则不再重复建任务、不发消息
        List<MediaTask> pendingTasks = mediaTaskMapper.findPendingByMediaId(media.getId());
        if (!pendingTasks.isEmpty()) {
            Media latest = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                    .orElseThrow(() -> new IllegalStateException("media disappeared after commit: " + fileId));
            return toDto(latest);
        }

        // 创建 media_task（PROCEDURE / PENDING）
        MediaTask task = new MediaTask();
        task.setMediaId(media.getId());
        task.setFileId(fileId);
        task.setType(MediaTaskType.PROCEDURE);
        task.setStatus(MediaTaskStatus.PENDING);
        task.setAttempt(0);
        mediaTaskMapper.insert(task);

        // 媒资进入处理中，并落盘上传方指定的试看秒数
        mediaMapper.updateUploaded(fileId, filename, size, MediaStatus.PROCESSING, previewSeconds);

        // 投递转码任务消息；发送失败时事务回滚，避免状态不一致
        boolean progressive = progressiveOverride != null
                ? progressiveOverride
                : abrProperties.progressiveEnabled();
        ProcedureTaskMessage.TaskType taskType = progressive
                ? ProcedureTaskMessage.TaskType.FAST
                : ProcedureTaskMessage.TaskType.FULL;
        ProcedureTaskMessage message = new ProcedureTaskMessage(
                fileId, media.getId(), media.getObjectKey(), task.getId(),
                taskType, progressive, previewSeconds);
        if (progressive) {
            mediaMapper.updateLadderFinished(fileId, MediaStatus.PROCESSING, null, LadderStatus.PENDING.code());
        }
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.ROUTING_KEY, message);

        Media updated = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new IllegalStateException("media disappeared after commit: " + fileId));
        return toDto(updated);
    }

    /**
     * null → 配置默认；&lt;=0 → 0（不试看）；其余封顶到 maxSeconds。
     */
    int resolvePreviewSeconds(Integer override) {
        if (override == null) {
            return previewProperties.seconds();
        }
        if (override <= 0) {
            return 0;
        }
        return Math.min(override, previewProperties.maxSeconds());
    }

    public MediaDto detail(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));
        return toDto(media);
    }

    /**
     * 步骤 14：删除媒资。
     */
    @Transactional
    public void delete(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        if (media.getStatus() == MediaStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media is processing, cannot delete: " + fileId);
        }
        if (!mediaTaskMapper.findPendingByMediaId(media.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media has running task, cannot delete: " + fileId);
        }

        minioStorage.removePrefix(ObjectKeys.rawPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.hlsPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.cover(fileId));

        mediaTaskMapper.deleteByMediaId(media.getId());
        mediaMapper.deleteByFileId(fileId);
    }

    public PageResult<MediaDto> list(String name, int pageNo, int pageSize) {
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        int offset = (pageNo - 1) * pageSize;
        long total = mediaMapper.countByFilename(name);
        List<MediaDto> records;
        if (total == 0) {
            records = List.of();
        } else {
            records = mediaMapper.pageByFilename(name, offset, pageSize).stream()
                    .map(this::toDto)
                    .toList();
        }
        int pages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResult<>(records, total, pageNo, pageSize, pages);
    }

    private MediaDto toDto(Media media) {
        return new MediaDto(
                media.getId(),
                media.getFileId(),
                media.getObjectKey(),
                media.getFilename(),
                media.getMediaUrl(),
                media.getCoverUrl(),
                media.getDuration(),
                media.getSize(),
                media.getStatus(),
                media.getStatus().label(),
                media.getPreviewSeconds(),
                media.getErrorMsg(),
                media.getCreateTime(),
                media.getUpdateTime()
        );
    }
}
