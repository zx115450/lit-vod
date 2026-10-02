package com.example.vod.common.domain.media;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * media_task 表访问。api 与 worker 共用：
 * <ul>
 *   <li>api：commit 时插入任务行、查询进行中任务（幂等）</li>
 *   <li>worker：开始时置 RUNNING+attempt+1，结束时置 SUCCESS / FAILED</li>
 * </ul>
 */
@Mapper
public interface MediaTaskMapper {

    @Select("SELECT id, media_id, file_id, type, status, attempt, payload, error_msg, created_at, finished_at " +
            "FROM media_task WHERE id = #{id}")
    MediaTask findById(Long id);

    @Select("SELECT id, media_id, file_id, type, status, attempt, payload, error_msg, created_at, finished_at " +
            "FROM media_task WHERE media_id = #{mediaId} AND status IN (0, 1)")
    List<MediaTask> findPendingByMediaId(Long mediaId);

    @Insert("INSERT INTO media_task (media_id, file_id, type, status, attempt, payload) " +
            "VALUES (#{mediaId}, #{fileId}, #{type}, #{status}, #{attempt}, #{payload})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(MediaTask task);

    /**
     * 开始执行：状态置 RUNNING，attempt+1。
     */
    @Update("UPDATE media_task SET status = #{status}, attempt = #{attempt} WHERE id = #{id}")
    int updateRunning(@Param("id") Long id,
                      @Param("status") MediaTaskStatus status,
                      @Param("attempt") int attempt);

    /**
     * 结束：状态置 SUCCESS / FAILED，写 error_msg 与 finished_at。
     */
    @Update("UPDATE media_task SET status = #{status}, error_msg = #{errorMsg}, finished_at = NOW() " +
            "WHERE id = #{id}")
    int updateFinished(@Param("id") Long id,
                       @Param("status") MediaTaskStatus status,
                       @Param("errorMsg") String errorMsg);

    /**
     * 按 media_id 硬删所有任务行（步骤 14：删除媒资）。
     */
    @Delete("DELETE FROM media_task WHERE media_id = #{mediaId}")
    int deleteByMediaId(@Param("mediaId") Long mediaId);
}
