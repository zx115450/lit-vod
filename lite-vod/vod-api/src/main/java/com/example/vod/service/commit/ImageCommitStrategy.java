package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.common.messaging.RabbitConfig;
import com.example.vod.common.messaging.ThumbnailTaskMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * IMAGE commit：创建 {@link MediaTaskType#THUMBNAIL}，投递 {@code vod.image.thumbnail}。
 * <p>禁止 PROCEDURE / HLS；{@code media_url} 保持 null。缩略图失败由 Worker 标 FINISHED 并保留原图。
 */
@Component
public class ImageCommitStrategy implements CommitStrategy {

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final RabbitTemplate rabbitTemplate;

    public ImageCommitStrategy(MediaMapper mediaMapper,
                               MediaTaskMapper mediaTaskMapper,
                               RabbitTemplate rabbitTemplate) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public AssetType assetType() {
        return AssetType.IMAGE;
    }

    @Override
    public void commit(CommitContext ctx) {
        Media media = ctx.media();
        String fileId = media.getFileId();

        MediaTask task = new MediaTask();
        task.setMediaId(media.getId());
        task.setFileId(fileId);
        task.setType(MediaTaskType.THUMBNAIL);
        task.setStatus(MediaTaskStatus.PENDING);
        task.setAttempt(0);
        mediaTaskMapper.insert(task);

        mediaMapper.updateUploaded(fileId, ctx.filename(), ctx.size(), MediaStatus.PROCESSING, 0);

        ThumbnailTaskMessage message = new ThumbnailTaskMessage(
                fileId, media.getId(), media.getObjectKey(), task.getId());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE_NAME,
                RabbitConfig.IMAGE_THUMBNAIL_ROUTING_KEY,
                message);
    }
}
