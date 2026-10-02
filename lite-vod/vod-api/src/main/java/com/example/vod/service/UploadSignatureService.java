package com.example.vod.service;

import com.example.vod.common.IdGenerator;
import com.example.vod.config.UploadProperties;
import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.PartEtag;
import com.example.vod.controller.dto.PartUrl;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import io.minio.messages.Part;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class UploadSignatureService {

    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(30);

    private final MediaMapper mediaMapper;
    private final MinioStorage minioStorage;
    private final UploadProperties uploadProps;

    public UploadSignatureService(MediaMapper mediaMapper,
                                   MinioStorage minioStorage,
                                   UploadProperties uploadProps) {
        this.mediaMapper = mediaMapper;
        this.minioStorage = minioStorage;
        this.uploadProps = uploadProps;
    }

    @Transactional
    public UploadSignatureResponse create() {
        return create(AssetType.VIDEO);
    }

    /**
     * 申请直传凭证并落库 UPLOADING。
     *
     * @param assetType 资产类型；缺省 VIDEO；CHAPTER 不可经上传创建
     */
    @Transactional
    public UploadSignatureResponse create(AssetType assetType) {
        AssetType type = assetType != null ? assetType : AssetType.VIDEO;
        if (!type.uploadable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "assetType " + type + " cannot be created via upload; CHAPTER is produced by split worker");
        }

        String fileId = IdGenerator.fileId();
        String objectKey = ObjectKeys.raw(fileId, type.defaultExtension());

        Media media = new Media();
        media.setFileId(fileId);
        media.setAssetType(type);
        media.setMimeType(type.defaultMimeType());
        media.setObjectKey(objectKey);
        media.setStatus(MediaStatus.UPLOADING);
        mediaMapper.insert(media);

        // 先落库再签发 URL；若落库失败，不会留下未登记的对象键。
        long expireAt = Instant.now().plus(DEFAULT_EXPIRY).getEpochSecond();
        String uploadUrl = minioStorage.presignedPut(objectKey, DEFAULT_EXPIRY);

        return new UploadSignatureResponse(fileId, uploadUrl, objectKey, expireAt);
    }

    // ==================== Multipart（二期） ====================

    /**
     * 申请 multipart 上传凭证：生成 fileId、落库 UPLOADING、CreateMultipartUpload、签发各片 URL。
     * <p>需 {@code vod.upload.multipart-enabled=true}，否则返回 501。
     */
    @Transactional
    public MultipartUploadSignatureResponse createMultipart(MultipartUploadRequest request) {
        if (!uploadProps.multipartEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "multipart upload is disabled (VOD_UPLOAD_MULTIPART_ENABLED=false)");
        }

        long partSize = resolvePartSize(request.partSize());
        long contentLength = request.contentLength();
        int partCount = (int) ((contentLength + partSize - 1) / partSize);
        if (partCount < 1) {
            partCount = 1;
        }
        if (partCount > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partCount exceeds 10000: " + partCount + ", increase partSize");
        }

        String fileId = IdGenerator.fileId();
        AssetType type = AssetType.VIDEO;
        String objectKey = ObjectKeys.raw(fileId, type.defaultExtension());

        Media media = new Media();
        media.setFileId(fileId);
        media.setAssetType(type);
        media.setMimeType(request.contentType() != null && !request.contentType().isBlank()
                ? request.contentType()
                : type.defaultMimeType());
        media.setObjectKey(objectKey);
        media.setStatus(MediaStatus.UPLOADING);
        mediaMapper.insert(media);

        String uploadId = minioStorage.createMultipartUpload(objectKey, request.contentType());

        long expireAt = Instant.now().plus(DEFAULT_EXPIRY).getEpochSecond();
        List<PartUrl> parts = new ArrayList<>(partCount);
        for (int n = 1; n <= partCount; n++) {
            String url = minioStorage.presignedUploadPart(objectKey, uploadId, n, DEFAULT_EXPIRY);
            parts.add(new PartUrl(n, url));
        }

        return new MultipartUploadSignatureResponse(
                fileId, objectKey, uploadId, partSize, partCount, parts, expireAt);
    }

    /**
     * 完成分片合并：校验 fileId 归属，调 MinIO CompleteMultipartUpload。
     * <p>成功后 objectKey 上才有完整对象，之后才能 commit。
     */
    @Transactional
    public void complete(String fileId, CompleteMultipartRequest request) {
        if (!uploadProps.multipartEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "multipart upload is disabled");
        }

        Media media = mediaMapper.findByFileId(fileId);
        if (media == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId);
        }
        if (media.getStatus() != MediaStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "media is not in UPLOADING state: " + fileId + " (" + media.getStatus() + ")");
        }

        List<PartEtag> sorted = request.parts().stream()
                .sorted(Comparator.comparingInt(PartEtag::partNumber))
                .toList();
        List<Part> minioParts = sorted.stream()
                .map(p -> new Part(p.partNumber(), p.etag()))
                .toList();

        minioStorage.completeMultipartUpload(media.getObjectKey(), request.uploadId(), minioParts);
    }

    /**
     * 中止分片上传：丢弃该 uploadId 下未完成分片，避免残留计费。
     */
    @Transactional
    public void abort(String fileId, AbortMultipartRequest request) {
        if (!uploadProps.multipartEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "multipart upload is disabled");
        }

        Media media = mediaMapper.findByFileId(fileId);
        if (media == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId);
        }

        minioStorage.abortMultipartUpload(media.getObjectKey(), request.uploadId());
    }

    private long resolvePartSize(long requested) {
        if (requested <= 0) {
            return uploadProps.defaultPartSize();
        }
        if (requested < uploadProps.minPartSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partSize must be >= " + uploadProps.minPartSize() + " bytes (5MiB)");
        }
        if (requested > uploadProps.maxPartSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "partSize must be <= " + uploadProps.maxPartSize() + " bytes (64MiB)");
        }
        return requested;
    }
}
