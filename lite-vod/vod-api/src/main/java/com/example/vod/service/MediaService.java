package com.example.vod.service;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.domain.media.MediaTaskMapper;
import com.example.vod.common.domain.media.ProcedureDeadLetterMapper;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.controller.dto.ChapterDto;
import com.example.vod.controller.dto.ChaptersResponse;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.service.commit.CommitContext;
import com.example.vod.service.commit.CommitStrategyRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class MediaService {

    private static final long MAX_SIZE_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB

    private final MediaMapper mediaMapper;
    private final MediaTaskMapper mediaTaskMapper;
    private final ProcedureDeadLetterMapper deadLetterMapper;
    private final MinioStorage minioStorage;
    private final CommitStrategyRegistry commitStrategyRegistry;

    public MediaService(MediaMapper mediaMapper,
                        MediaTaskMapper mediaTaskMapper,
                        ProcedureDeadLetterMapper deadLetterMapper,
                        MinioStorage minioStorage,
                        CommitStrategyRegistry commitStrategyRegistry) {
        this.mediaMapper = mediaMapper;
        this.mediaTaskMapper = mediaTaskMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.minioStorage = minioStorage;
        this.commitStrategyRegistry = commitStrategyRegistry;
    }

    @Transactional
    public MediaDto commit(String fileId, String filename) {
        return commit(fileId, filename, null, null, null, null);
    }

    @Transactional
    public MediaDto commit(String fileId, String filename, Boolean progressiveOverride) {
        return commit(fileId, filename, progressiveOverride, null, null, null);
    }

    @Transactional
    public MediaDto commit(String fileId, String filename, Boolean progressiveOverride,
                           Integer previewSecondsOverride) {
        return commit(fileId, filename, progressiveOverride, previewSecondsOverride, null, null);
    }

    /**
     * 确认直传完成，按 {@link AssetType} 经策略注册表分流建任务。
     *
     * @param progressiveOverride    {@code null} 用配置；仅 VIDEO 有效
     * @param previewSecondsOverride {@code null} 用配置；仅 VIDEO 有效
     * @param requestAssetType       请求体可选；非空时须与库中一致
     * @param splitRule              仅 DOCUMENT 有效；缺省 {@link SplitRule#MARKDOWN}
     */
    @Transactional
    public MediaDto commit(String fileId, String filename, Boolean progressiveOverride,
                           Integer previewSecondsOverride, AssetType requestAssetType,
                           SplitRule splitRule) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        if (media.getStatus() == MediaStatus.FINISHED || media.getStatus() == MediaStatus.PLAYABLE) {
            return toDto(media);
        }

        if (!minioStorage.head(media.getObjectKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "未找到上传对象，请先完成直传: " + media.getObjectKey());
        }

        long size = minioStorage.statSize(media.getObjectKey());
        if (size > MAX_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file size exceeds limit: " + size);
        }

        if (!mediaTaskMapper.findPendingByMediaId(media.getId()).isEmpty()) {
            Media latest = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                    .orElseThrow(() -> new IllegalStateException("media disappeared after commit: " + fileId));
            return toDto(latest);
        }

        AssetType stored = media.getAssetType() != null ? media.getAssetType() : AssetType.VIDEO;
        if (requestAssetType != null && requestAssetType != stored) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "assetType mismatch: request=" + requestAssetType + ", stored=" + stored);
        }

        CommitContext ctx = new CommitContext(
                media, filename, size, progressiveOverride, previewSecondsOverride, splitRule);
        commitStrategyRegistry.get(stored).commit(ctx);

        Media updated = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new IllegalStateException("media disappeared after commit: " + fileId));
        return toDto(updated);
    }

    public MediaDto detail(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));
        return toDto(media);
    }

    /**
     * DOCUMENT 章目录。父不存在 → 404；PROCESSING / FAILED / 无子章 → 200 + 空数组。
     */
    public ChaptersResponse listChapters(String sourceFileId) {
        Media parent = Optional.ofNullable(mediaMapper.findByFileId(sourceFileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "media not found: " + sourceFileId));
        // 非 DOCUMENT 也可查询（无子章则空）；便于书城轮询时不区分状态
        List<ChapterDto> chapters = mediaMapper.findChaptersByParentFileId(parent.getFileId()).stream()
                .map(this::toChapterDto)
                .toList();
        return new ChaptersResponse(sourceFileId, chapters);
    }

    /**
     * 删除媒资。VIDEO 仍按步骤 14：清 {@code raw/}、{@code hls/}、{@code cover/} 再删行。
     * <p>DOCUMENT：先清 {@code chap/{fileId}/} 与全部子 CHAPTER 行（含其任务），再删原件与父行。
     * 不查书城是否仍引用。
     * <p>CHAPTER：允许单独删除（管理清理），只删该章对象与本行，不删父 DOCUMENT。
     * <p>IMAGE：额外清 {@code img/{fileId}/}。处理中或仍有进行中任务 → 409。
     */
    @Transactional
    public void delete(String fileId) {
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        if (media.getStatus() == MediaStatus.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media is processing, cannot delete: " + fileId);
        }
        if (!mediaTaskMapper.findPendingByMediaId(media.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media has running task, cannot delete: " + fileId);
        }

        AssetType type = media.getAssetType() != null ? media.getAssetType() : AssetType.VIDEO;
        List<Media> chapters = type == AssetType.DOCUMENT
                ? mediaMapper.findChaptersByParentFileId(fileId)
                : List.of();

        // 对象先于库：任一步失败则不删行，避免库无记录但桶内残留
        if (type == AssetType.DOCUMENT) {
            minioStorage.removePrefix(ObjectKeys.chapterPrefix(fileId));
        } else if (type == AssetType.IMAGE) {
            minioStorage.removePrefix(ObjectKeys.imagePrefix(fileId));
        } else if (type == AssetType.CHAPTER
                && media.getObjectKey() != null
                && !media.getObjectKey().isBlank()) {
            minioStorage.removePrefix(media.getObjectKey());
        }
        minioStorage.removePrefix(ObjectKeys.rawPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.hlsPrefix(fileId));
        minioStorage.removePrefix(ObjectKeys.cover(fileId));

        for (Media chapter : chapters) {
            if (chapter.getId() == null) {
                continue;
            }
            deadLetterMapper.deleteByMediaId(chapter.getId());
            mediaTaskMapper.deleteByMediaId(chapter.getId());
        }
        if (type == AssetType.DOCUMENT) {
            mediaMapper.deleteByParentFileId(fileId);
        }

        deadLetterMapper.deleteByMediaId(media.getId());
        mediaTaskMapper.deleteByMediaId(media.getId());
        mediaMapper.deleteByFileId(fileId);
    }

    public PageResult<MediaDto> list(String name, int pageNo, int pageSize) {
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        int offset = (pageNo - 1) * pageSize;
        long total = mediaMapper.countByFilename(name);
        List<MediaDto> records;
        if (total == 0) {
            records = List.of();
        } else {
            records = mediaMapper.pageByFilename(name, offset, pageSize).stream()
                    .map(this::toDto)
                    .toList();
        }
        int pages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResult<>(records, total, pageNo, pageSize, pages);
    }

    private MediaDto toDto(Media media) {
        AssetType assetType = media.getAssetType() != null ? media.getAssetType() : AssetType.VIDEO;
        return new MediaDto(
                media.getId(),
                media.getFileId(),
                assetType,
                media.getObjectKey(),
                media.getFilename(),
                media.getMimeType(),
                media.getParentFileId(),
                media.getChapterNo(),
                media.getPageCount(),
                media.getMediaUrl(),
                media.getCoverUrl(),
                media.getDuration(),
                media.getSize(),
                media.getStatus(),
                media.getStatus().label(),
                media.getPreviewSeconds(),
                media.getErrorMsg(),
                media.getCreateTime(),
                media.getUpdateTime()
        );
    }

    private ChapterDto toChapterDto(Media media) {
        int wordCount = media.getPageCount() != null ? media.getPageCount() : 0;
        String title = media.getFilename() != null ? media.getFilename() : "";
        return new ChapterDto(
                media.getChapterNo() != null ? media.getChapterNo() : 0,
                title,
                media.getFileId(),
                wordCount,
                AssetType.CHAPTER
        );
    }
}
