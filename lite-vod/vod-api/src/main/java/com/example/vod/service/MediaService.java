package com.example.vod.service;

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

    public MediaService(MediaMapper mediaMapper,
                        MediaTaskMapper mediaTaskMapper,
                        MinioStorage minioStorage,
                        RabbitTemplate rabbitTemplate) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.minioStorage = minioStorage;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public MediaDto commit(String fileId, String filename) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        // 已处理完成的媒资直接幂等返回
        if (media.getStatus() == MediaStatus.FINISHED) {
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

        // 媒资进入处理中
        mediaMapper.updateUploaded(fileId, filename, size, MediaStatus.PROCESSING);

        // 投递转码任务消息；发送失败时事务回滚，避免状态不一致
        ProcedureTaskMessage message = new ProcedureTaskMessage(
                fileId, media.getId(), media.getObjectKey(), task.getId());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.ROUTING_KEY, message);

        Media updated = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new IllegalStateException("media disappeared after commit: " + fileId));
        return toDto(updated);
    }

    public MediaDto detail(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));
        return toDto(media);
    }

    /**
     * 步骤 14：删除媒资。
     * <ul>
     *   <li>不存在：404</li>
     *   <li>处理中（media.status == PROCESSING 或存在 PENDING/RUNNING 任务）：409，避免 Worker 写回已删记录</li>
     *   <li>先删 MinIO 对象（raw/hls 前缀 + cover），再硬删 media_task 与 media；
     *       对象删失败则整体失败，避免库无记录但桶内残留占磁盘</li>
     *   <li>成功：204</li>
     * </ul>
     */
    @Transactional
    public void delete(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        // 处理中拒绝删除：media 处于 PROCESSING，或仍有未完结任务
        if (media.getStatus() == MediaStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media is processing, cannot delete: " + fileId);
        }
        if (!mediaTaskMapper.findPendingByMediaId(media.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media has running task, cannot delete: " + fileId);
        }

        // 先删对象（失败抛 IllegalStateException → 500，DB 未提交）
        minioStorage.removePrefix(ObjectKeys.rawPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.hlsPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.cover(fileId));

        // 再删库：先任务行，再媒资行（media.id 被任务行引用）
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
                media.getErrorMsg(),
                media.getCreateTime(),
                media.getUpdateTime()
        );
    }
}
