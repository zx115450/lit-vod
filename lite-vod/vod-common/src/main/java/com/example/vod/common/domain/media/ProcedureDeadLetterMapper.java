package com.example.vod.common.domain.media;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 转码死信表。同一 task_id 重复停放时覆盖失败原因和次数。
 */
@Mapper
public interface ProcedureDeadLetterMapper {

    @Insert("INSERT INTO procedure_dead_letter " +
            "(task_id, media_id, file_id, object_key, task_type, progressive, preview_seconds, attempt, error_msg) " +
            "VALUES (#{taskId}, #{mediaId}, #{fileId}, #{objectKey}, #{taskType}, #{progressive}, " +
            "#{previewSeconds}, #{attempt}, #{errorMsg}) " +
            "ON DUPLICATE KEY UPDATE " +
            "attempt = VALUES(attempt), error_msg = VALUES(error_msg), object_key = VALUES(object_key), " +
            "task_type = VALUES(task_type), progressive = VALUES(progressive), " +
            "preview_seconds = VALUES(preview_seconds)")
    int upsert(ProcedureDeadLetter row);

    @Delete("DELETE FROM procedure_dead_letter WHERE media_id = #{mediaId}")
    int deleteByMediaId(@Param("mediaId") Long mediaId);
}
