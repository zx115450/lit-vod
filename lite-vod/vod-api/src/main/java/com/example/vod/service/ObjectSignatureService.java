package com.example.vod.service;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.controller.dto.ObjectSignatureResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * DOCUMENT / CHAPTER / IMAGE 对象预签名签发。
 *
 * <p>只返回限时 GET URL，不在本进程读正文；VIDEO 请走 {@link PlaySignatureService}。
 */
@Service
public class ObjectSignatureService {

    private static final int DEFAULT_TTL_SECONDS = 60;
    private static final int MAX_TTL_SECONDS = 600;
    private static final Set<AssetType> SIGNABLE = EnumSet.of(
            AssetType.DOCUMENT, AssetType.CHAPTER, AssetType.IMAGE);

    private final MediaMapper mediaMapper;
    private final MinioStorage minioStorage;

    public ObjectSignatureService(MediaMapper mediaMapper, MinioStorage minioStorage) {
        this.mediaMapper = mediaMapper;
        this.minioStorage = minioStorage;
    }

    public ObjectSignatureResponse sign(String fileId, Integer ttlSeconds) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileId is required");
        }
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        AssetType assetType = media.getAssetType() != null ? media.getAssetType() : AssetType.VIDEO;
        if (!SIGNABLE.contains(assetType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "object signature only for DOCUMENT / CHAPTER / IMAGE");
        }
        if (media.getObjectKey() == null || media.getObjectKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "media has no objectKey");
        }

        int ttl = resolveTtl(ttlSeconds);
        String objectUrl = minioStorage.presignedGet(media.getObjectKey(), Duration.ofSeconds(ttl));
        long expireAt = Instant.now().getEpochSecond() + ttl;
        return new ObjectSignatureResponse(
                fileId, objectUrl, media.getObjectKey(), assetType, expireAt);
    }

    private static int resolveTtl(Integer ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_TTL_SECONDS;
        }
        if (ttlSeconds < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ttl must be >= 1");
        }
        return Math.min(ttlSeconds, MAX_TTL_SECONDS);
    }
}
