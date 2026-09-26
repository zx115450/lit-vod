-- 已有库增量：试看秒数由上传方在 commit 时指定
-- 若列已存在会报 Duplicate column，可忽略。
USE lite_vod;

ALTER TABLE media
    ADD COLUMN preview_seconds INT DEFAULT NULL
        COMMENT '试看秒数（上传方指定，Worker 据此出 preview.m3u8；0/空表示不生成试看）'
        AFTER ladder_status;
