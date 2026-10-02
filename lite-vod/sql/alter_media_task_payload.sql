-- L2：media_task 扩展 payload，存放 splitRule 等（JSON 字符串）
-- 若列已存在会报 Duplicate column，可忽略。
USE lite_vod;

ALTER TABLE media_task
  ADD COLUMN payload VARCHAR(512) NULL COMMENT '任务扩展 JSON，如 {"splitRule":"MARKDOWN"}' AFTER attempt;
