package com.example.vod.common.domain.media;

/**
 * media_task 任务类型，直接以枚举名称落库（VARCHAR）。
 * <p>{@link #EXTRACT_TEXT} / {@link #SPLIT_CHAPTER} / {@link #THUMBNAIL} 供 L2～L5 使用；
 * L1 仍仅投递 {@link #PROCEDURE}。
 */
public enum MediaTaskType {
    TRANSCODE,
    COVER,
    PROCEDURE,
    EXTRACT_TEXT,
    SPLIT_CHAPTER,
    THUMBNAIL
}
