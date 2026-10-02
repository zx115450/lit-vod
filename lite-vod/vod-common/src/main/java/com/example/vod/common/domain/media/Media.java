package com.example.vod.common.domain.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 媒资实体，对应 media 表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Media {

    private Long id;
    private String fileId;
    /** 资产类型，缺省 VIDEO。 */
    private AssetType assetType;
    private String objectKey;
    private String filename;
    private String mimeType;
    /** CHAPTER 挂 DOCUMENT；字幕挂 VIDEO。 */
    private String parentFileId;
    /** CHAPTER 序号，从 1 起。 */
    private Integer chapterNo;
    /**
     * 文档页数；CHAPTER 行 MVP 复用为 {@code wordCount}（章正文 Unicode 码点数）。
     */
    private Integer pageCount;
    /** PDF 抽取文本对象键。 */
    private String extractKey;
    private String mediaUrl;
    private String coverUrl;
    private Float duration;
    private Long size;
    private MediaStatus status;
    private Integer ladderStatus;
    /** 试看秒数：上传方指定；null/0 表示不生成试看清单。 */
    private Integer previewSeconds;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
