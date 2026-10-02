package com.example.vod.service.commit;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.LadderStatus;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTask;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.MediaTaskStatus;
import com.example.vod.common.domain.media.MediaTaskType;
import com.example.vod.common.messaging.ProcedureTaskMessage;
import com.example.vod.common.messaging.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * VIDEO commit：创建 PROCEDURE 任务，投递 {@code vod.procedure}（行为与 L1 一致）。
 */
@Component
public class VideoCommitStrategy implements CommitStrategy {

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final AbrProperties abrProperties;
    private final PreviewProperties previewProperties;

    public VideoCommitStrategy(MediaMapper mediaMapper,
                               MediaTaskMapper mediaTaskMapper,
                               RabbitTemplate rabbitTemplate,
                               AbrProperties abrProperties,
                               PreviewProperties previewProperties) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.abrProperties = abrProperties;
        this.previewProperties = previewProperties;
    }

    @Override
    public AssetType assetType() {
        return AssetType.VIDEO;
    }

    @Override
    public void commit(CommitContext ctx) {
        Media media = ctx.media();
        String fileId = media.getFileId();
        int previewSeconds = resolvePreviewSeconds(ctx.previewSecondsOverride());

        MediaTask task = new MediaTask();
        task.setMediaId(media.getId());
        task.setFileId(fileId);
        task.setType(MediaTaskType.PROCEDURE);
        task.setStatus(MediaTaskStatus.PENDING);
        task.setAttempt(0);
        mediaTaskMapper.insert(task);

        mediaMapper.updateUploaded(fileId, ctx.filename(), ctx.size(), MediaStatus.PROCESSING, previewSeconds);

        boolean progressive = ctx.progressiveOverride() != null
                ? ctx.progressiveOverride()
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
    }

    private int resolvePreviewSeconds(Integer override) {
        if (override == null) {
            return previewProperties.seconds();
        }
        if (override <= 0) {
            return 0;
        }
        return Math.min(override, previewProperties.maxSeconds());
    }
}
