package com.example.vod.service;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.gateway.PlayPathSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 播放签名签发业务，对应步骤 10。
 *
 * <p>VOD 内核只负责签发可播放地址，不负责课表鉴权；对接天机时由 tj-media 先鉴权再调本接口。
 *
 * <p>仅当 {@link MediaStatus#playable()}（PLAYABLE / FINISHED）才签发，否则 4xx。
 * 试看模式：时长取媒资上的 {@code previewSeconds}（上传方决定），L2 开启时 path 绑 {@code preview.m3u8}。
 */
@Service
public class PlaySignatureService {

    private final MediaMapper mediaMapper;
    private final PlaySignService playSignService;
    private final PreviewProperties previewProperties;
    private final MinioStorage minioStorage;

    public PlaySignatureService(MediaMapper mediaMapper,
                                PlaySignService playSignService,
                                PreviewProperties previewProperties,
                                MinioStorage minioStorage) {
        this.mediaMapper = mediaMapper;
        this.playSignService = playSignService;
        this.previewProperties = previewProperties;
        this.minioStorage = minioStorage;
    }

    /**
     * 签发可播放 URL。
     *
     * @param fileId  必填
     * @param preview {@code true}=试看（时长用媒资 previewSeconds）；{@code false}=正片
     */
    public PlaySignatureResponse sign(String fileId, boolean preview) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileId is required");
        }
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        if (!media.getStatus().playable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "media not processed yet, current status: " + media.getStatus());
        }

        int experSeconds = 0;
        String path = PlayPathSupport.signedPlaylistPath(fileId, media.getMediaUrl());

        if (preview) {
            experSeconds = media.getPreviewSeconds() == null ? 0 : media.getPreviewSeconds();
            if (experSeconds <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "preview not configured for this media (previewSeconds<=0)");
            }
            if (previewProperties.l2Enabled()) {
                if (!minioStorage.exists(ObjectKeys.hlsPreview(fileId))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "preview.m3u8 not found; re-transcode with L2 enabled");
                }
                path = PlayPathSupport.previewPlaylistPath(fileId);
            }
            // L2 关闭：仍签发正片 path，exper 带上传方秒数（L0/L1）
        }

        long expireAt = playSignService.nowEpoch() + playSignService.ttlSeconds();
        String sign = playSignService.sign(path, expireAt, experSeconds);

        String playUrl = String.format("%s%s?e=%d&exper=%d&sign=%s",
                stripTrailingSlash(playSignService.publicBase()), path, expireAt, experSeconds, sign);

        return new PlaySignatureResponse(fileId, playUrl, sign, expireAt);
    }

    private static String stripTrailingSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
