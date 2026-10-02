package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.common.messaging.DocumentSplitTaskMessage;
import com.example.vod.common.messaging.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * DOCUMENT commit：创建 {@link MediaTaskType#SPLIT_CHAPTER}，投递 {@code vod.document.split}。
 * <p>禁止 PROCEDURE / HLS；{@code media_url} 保持 null。
 */
@Component
public class DocumentCommitStrategy implements CommitStrategy {

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final RabbitTemplate rabbitTemplate;

    public DocumentCommitStrategy(MediaMapper mediaMapper,
                                  MediaTaskMapper mediaTaskMapper,
                                  RabbitTemplate rabbitTemplate) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public AssetType assetType() {
        return AssetType.DOCUMENT;
    }

    @Override
    public void commit(CommitContext ctx) {
        Media media = ctx.media();
        String fileId = media.getFileId();
        SplitRule rule = ctx.splitRule() != null ? ctx.splitRule() : SplitRule.MARKDOWN;
        String payload = "{\"splitRule\":\"" + rule.name() + "\"}";

        MediaTask task = new MediaTask();
        task.setMediaId(media.getId());
        task.setFileId(fileId);
        task.setType(MediaTaskType.SPLIT_CHAPTER);
        task.setStatus(MediaTaskStatus.PENDING);
        task.setAttempt(0);
        task.setPayload(payload);
        mediaTaskMapper.insert(task);

        // DOCUMENT 无试看秒数；previewSeconds 写 0
        mediaMapper.updateUploaded(fileId, ctx.filename(), ctx.size(), MediaStatus.PROCESSING, 0);

        DocumentSplitTaskMessage message = new DocumentSplitTaskMessage(
                fileId, media.getId(), media.getObjectKey(), task.getId(), rule);
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE_NAME,
                RabbitConfig.DOCUMENT_SPLIT_ROUTING_KEY,
                message);
    }
}
