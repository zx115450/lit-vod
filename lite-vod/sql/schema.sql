-- lite-vod Phase 0：media / media_task
-- 对齐《轻量版云点播》实现文档 5.1、5.2
-- 库名由 Compose MYSQL_DATABASE 创建（建议 lite_vod），此处不建库

USE lite_vod;

CREATE TABLE IF NOT EXISTS media (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_id      VARCHAR(64)  NOT NULL COMMENT '对外唯一标识，对齐腾讯 fileId',
    object_key   VARCHAR(512) NOT NULL COMMENT '原始对象键，如 raw/{fileId}/source.mp4',
    filename     VARCHAR(255)          DEFAULT NULL COMMENT '原始文件名',
    media_url    VARCHAR(512)          DEFAULT NULL COMMENT '播放入口，如 hls/{fileId}/index.m3u8',
    cover_url    VARCHAR(512)          DEFAULT NULL COMMENT '封面对象路径',
    duration     FLOAT                 DEFAULT NULL COMMENT '时长（秒）',
    size         BIGINT                DEFAULT NULL COMMENT '大小（字节）',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0上传中 1已上传 2处理中 3可播 4完成 5失败',
    ladder_status TINYINT              DEFAULT NULL COMMENT '渐进式多档：0待补档 1补档中 2档位齐全 3部分档位失败',
    error_msg    VARCHAR(512)          DEFAULT NULL COMMENT '失败原因',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒资';

CREATE TABLE IF NOT EXISTS media_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    media_id     BIGINT       NOT NULL COMMENT '关联 media.id',
    file_id      VARCHAR(64)  NOT NULL COMMENT '冗余 fileId，便于日志',
    type         VARCHAR(32)  NOT NULL DEFAULT 'PROCEDURE' COMMENT 'TRANSCODE / COVER / PROCEDURE',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 PENDING 1 RUNNING 2 SUCCESS 3 FAILED',
    attempt      INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    error_msg    VARCHAR(512)          DEFAULT NULL COMMENT '失败原因',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finished_at  DATETIME              DEFAULT NULL COMMENT '结束时间',
    PRIMARY KEY (id),
    KEY idx_media_task_media_id (media_id),
    KEY idx_media_task_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒资处理任务';
