package com.example.vod.common.domain.media;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * media 表访问。api 与 worker 共用：
 * <ul>
 *   <li>api：commit 时创建 / 查询 / 列表 / 更新上传状态</li>
 *   <li>worker：转码成功 / 失败时回写状态与产物地址</li>
 * </ul>
 */
@Mapper
public interface MediaMapper {

    @Select("SELECT id, file_id, asset_type, object_key, filename, mime_type, parent_file_id, chapter_no, page_count, " +
            "extract_key, media_url, cover_url, duration, size, status, ladder_status, " +
            "preview_seconds, error_msg, create_time, update_time " +
            "FROM media WHERE file_id = #{fileId}")
    Media findByFileId(String fileId);

    @Insert("INSERT INTO media (file_id, asset_type, object_key, filename, mime_type, parent_file_id, chapter_no, " +
            "page_count, extract_key, media_url, cover_url, duration, size, status, " +
            "ladder_status, preview_seconds, error_msg) " +
            "VALUES (#{fileId}, #{assetType}, #{objectKey}, #{filename}, #{mimeType}, #{parentFileId}, #{chapterNo}, " +
            "#{pageCount}, #{extractKey}, #{mediaUrl}, #{coverUrl}, #{duration}, #{size}, " +
            "#{status}, #{ladderStatus}, #{previewSeconds}, #{errorMsg})")
    void insert(Media media);

    @Update("UPDATE media SET filename = #{filename}, size = #{size}, status = #{status}, " +
            "preview_seconds = #{previewSeconds}, update_time = NOW() " +
            "WHERE file_id = #{fileId}")
    int updateUploaded(@Param("fileId") String fileId,
                       @Param("filename") String filename,
                       @Param("size") long size,
                       @Param("status") MediaStatus status,
                       @Param("previewSeconds") Integer previewSeconds);

    /**
     * 仅推进状态（DOCUMENT 切章完成等：不写 media_url / HLS）。
     */
    @Update("UPDATE media SET status = #{status}, error_msg = NULL, update_time = NOW() " +
            "WHERE file_id = #{fileId}")
    int updateStatus(@Param("fileId") String fileId,
                     @Param("status") MediaStatus status);

    /**
     * IMAGE 缩略图结束：可改写 {@code object_key} / {@code cover_url}。
     * 不写 {@code media_url}（无 HLS）。失败时 objectKey 仍为原图，status 仍为 FINISHED。
     */
    @Update("UPDATE media SET status = #{status}, object_key = #{objectKey}, cover_url = #{coverUrl}, " +
            "error_msg = #{errorMsg}, update_time = NOW() WHERE file_id = #{fileId}")
    int updateThumbnailResult(@Param("fileId") String fileId,
                              @Param("status") MediaStatus status,
                              @Param("objectKey") String objectKey,
                              @Param("coverUrl") String coverUrl,
                              @Param("errorMsg") String errorMsg);

    /**
     * 转码成功：写回 media_url / cover_url / duration，状态置 FINISHED，清空 error_msg。
     */
    @Update("UPDATE media SET status = #{status}, media_url = #{mediaUrl}, cover_url = #{coverUrl}, " +
            "duration = #{duration}, error_msg = NULL, update_time = NOW() WHERE file_id = #{fileId}")
    int updateProcessed(@Param("fileId") String fileId,
                        @Param("status") MediaStatus status,
                        @Param("mediaUrl") String mediaUrl,
                        @Param("coverUrl") String coverUrl,
                        @Param("duration") float duration);

    /**
     * 补档完成：标 FINISHED 与 ladder_status。
     * 渐进式下 media_url 已在快路径写入，这里不覆盖；cover_url 可刷新。
     */
    @Update("UPDATE media SET status = #{status}, cover_url = #{coverUrl}, " +
            "ladder_status = #{ladderStatus}, error_msg = NULL, update_time = NOW() WHERE file_id = #{fileId}")
    int updateLadderFinished(@Param("fileId") String fileId,
                             @Param("status") MediaStatus status,
                             @Param("coverUrl") String coverUrl,
                             @Param("ladderStatus") Integer ladderStatus);

    /**
     * 转码失败：状态置 FAILED，写 error_msg（调用方负责截断到 512）。
     */
    @Update("UPDATE media SET status = #{status}, error_msg = #{errorMsg}, update_time = NOW() " +
            "WHERE file_id = #{fileId}")
    int updateFailed(@Param("fileId") String fileId,
                     @Param("status") MediaStatus status,
                     @Param("errorMsg") String errorMsg);

    @Select("<script>" +
            "SELECT id, file_id, asset_type, object_key, filename, mime_type, parent_file_id, chapter_no, page_count, " +
            "extract_key, media_url, cover_url, duration, size, status, ladder_status, " +
            "preview_seconds, error_msg, create_time, update_time " +
            "FROM media " +
            "<where>" +
            "<if test='name != null and name != \"\"'>filename LIKE CONCAT('%', #{name}, '%')</if>" +
            "</where>" +
            "ORDER BY create_time DESC LIMIT #{pageSize} OFFSET #{offset}" +
            "</script>")
    List<Media> pageByFilename(@Param("name") String name,
                               @Param("offset") int offset,
                               @Param("pageSize") int pageSize);

    @Select("<script>" +
            "SELECT COUNT(*) FROM media " +
            "<where>" +
            "<if test='name != null and name != \"\"'>filename LIKE CONCAT('%', #{name}, '%')</if>" +
            "</where>" +
            "</script>")
    long countByFilename(@Param("name") String name);

    /**
     * 按 fileId 硬删 media 行（步骤 14：删除媒资）。
     * 调用方应已确保对象已清理，避免库无记录但桶内残留。
     */
    @Delete("DELETE FROM media WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") String fileId);

    /**
     * 列出 DOCUMENT 下全部 CHAPTER，按 chapter_no 升序。
     */
    @Select("SELECT id, file_id, asset_type, object_key, filename, mime_type, parent_file_id, chapter_no, page_count, " +
            "extract_key, media_url, cover_url, duration, size, status, ladder_status, " +
            "preview_seconds, error_msg, create_time, update_time " +
            "FROM media WHERE parent_file_id = #{parentFileId} AND asset_type = 'CHAPTER' " +
            "ORDER BY chapter_no ASC")
    List<Media> findChaptersByParentFileId(@Param("parentFileId") String parentFileId);

    /**
     * 删除某 DOCUMENT 下全部 CHAPTER 行（切章失败回滚 / 重切前清理）。
     */
    @Delete("DELETE FROM media WHERE parent_file_id = #{parentFileId} AND asset_type = 'CHAPTER'")
    int deleteByParentFileId(@Param("parentFileId") String parentFileId);
}
