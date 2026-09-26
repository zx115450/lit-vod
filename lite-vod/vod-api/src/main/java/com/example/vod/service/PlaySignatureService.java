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
 * 试看 L2 开启且 {@code exper>0} 且桶内已有 preview 时，path 绑 {@code preview.m3u8}；
 * 否则回退正片清单（L1 / 首期行为）。
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
     * @param fileId 必填
     * @param exper  试看秒数，&lt;=0 视为 0（不试看）
     */
    public PlaySignatureResponse sign(String fileId, int exper) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileId is required");
        }
        Media media = Optional.ofNullable(mediaMapper.findByFileId(fileId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found: " + fileId));

        if (!media.getStatus().playable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "media not processed yet, current status: " + media.getStatus());
        }

        int experSeconds = Math.max(0, exper);
        long expireAt = playSignService.nowEpoch() + playSignService.ttlSeconds();

        String path = resolveSignPath(fileId, media.getMediaUrl(), experSeconds);
        String sign = playSignService.sign(path, expireAt, experSeconds);

        String playUrl = String.format("%s%s?e=%d&exper=%d&sign=%s",
                stripTrailingSlash(playSignService.publicBase()), path, expireAt, experSeconds, sign);

        return new PlaySignatureResponse(fileId, playUrl, sign, expireAt);
    }

    private String resolveSignPath(String fileId, String mediaUrl, int experSeconds) {
        if (previewProperties.l2Enabled()
                && experSeconds > 0
                && minioStorage.exists(ObjectKeys.hlsPreview(fileId))) {
            return PlayPathSupport.previewPlaylistPath(fileId);
        }
        return PlayPathSupport.signedPlaylistPath(fileId, mediaUrl);
    }

    private static String stripTrailingSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
