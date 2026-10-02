-- 已有库增量：多资产类型（L1）
-- 若列已存在会报 Duplicate column，可忽略。
USE lite_vod;

ALTER TABLE media
  ADD COLUMN asset_type      VARCHAR(16)  NOT NULL DEFAULT 'VIDEO'
    COMMENT 'VIDEO/DOCUMENT/CHAPTER/IMAGE/AUDIO/SUBTITLE' AFTER file_id,
  ADD COLUMN mime_type       VARCHAR(128) NULL AFTER filename,
  ADD COLUMN parent_file_id  VARCHAR(64)  NULL
    COMMENT 'CHAPTER 挂 DOCUMENT；字幕挂 VIDEO' AFTER mime_type,
  ADD COLUMN chapter_no      INT          NULL COMMENT 'CHAPTER 序号，从 1 起' AFTER parent_file_id,
  ADD COLUMN page_count      INT          NULL COMMENT '文档页数' AFTER chapter_no,
  ADD COLUMN extract_key     VARCHAR(512) NULL COMMENT 'PDF 抽取文本对象键' AFTER page_count;

CREATE INDEX idx_media_parent ON media (parent_file_id);

-- media_task.type 注释扩展（列类型不变，仍为 VARCHAR）
ALTER TABLE media_task
  MODIFY COLUMN type VARCHAR(32) NOT NULL DEFAULT 'PROCEDURE'
    COMMENT 'PROCEDURE/TRANSCODE/COVER/EXTRACT_TEXT/SPLIT_CHAPTER/THUMBNAIL';
