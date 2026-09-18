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
    private String objectKey;
    private String filename;
    private String mediaUrl;
    private String coverUrl;
    private Float duration;
    private Long size;
    private MediaStatus status;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
