package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.SplitRule;

/**
 * commit 策略执行上下文：公共校验已通过，策略只负责建任务与投递。
 */
public final class CommitContext {

    private final Media media;
    private final String filename;
    private final long size;
    private final Boolean progressiveOverride;
    private final Integer previewSecondsOverride;
    private final SplitRule splitRule;

    public CommitContext(Media media,
                         String filename,
                         long size,
                         Boolean progressiveOverride,
                         Integer previewSecondsOverride,
                         SplitRule splitRule) {
        this.media = media;
        this.filename = filename;
        this.size = size;
        this.progressiveOverride = progressiveOverride;
        this.previewSecondsOverride = previewSecondsOverride;
        this.splitRule = splitRule;
    }

    public Media media() {
        return media;
    }

    public String filename() {
        return filename;
    }

    public long size() {
        return size;
    }

    public Boolean progressiveOverride() {
        return progressiveOverride;
    }

    public Integer previewSecondsOverride() {
        return previewSecondsOverride;
    }

    public SplitRule splitRule() {
        return splitRule;
    }

    public AssetType assetType() {
        return media.getAssetType() != null ? media.getAssetType() : AssetType.VIDEO;
    }
}
