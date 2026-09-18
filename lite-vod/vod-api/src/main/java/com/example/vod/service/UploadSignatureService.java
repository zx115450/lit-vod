package com.example.vod.service;

import com.example.vod.common.IdGenerator;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class UploadSignatureService {

    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(30);

    private final MediaMapper mediaMapper;
    private final MinioStorage minioStorage;

    public UploadSignatureService(MediaMapper mediaMapper, MinioStorage minioStorage) {
        this.mediaMapper = mediaMapper;
        this.minioStorage = minioStorage;
    }

    @Transactional
    public UploadSignatureResponse create() {
        String fileId = IdGenerator.fileId();
        String objectKey = ObjectKeys.raw(fileId);

        Media media = new Media();
        media.setFileId(fileId);
        media.setObjectKey(objectKey);
        media.setStatus(MediaStatus.UPLOADING);
        mediaMapper.insert(media);

        // 先落库再签发 URL；若落库失败，不会留下未登记的对象键。
        long expireAt = Instant.now().plus(DEFAULT_EXPIRY).getEpochSecond();
        String uploadUrl = minioStorage.presignedPut(objectKey, DEFAULT_EXPIRY);

        return new UploadSignatureResponse(fileId, uploadUrl, objectKey, expireAt);
    }
}
