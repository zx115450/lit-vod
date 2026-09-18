package com.example.vod.service;

import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.controller.dto.PlaySignatureResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * 播放签名签发业务，对应步骤 10。
 *
 * <p>VOD 内核只负责签发可播放地址，不负责课表鉴权；对接天机时由 tj-media 先鉴权再调本接口。
 *
 * <p>仅当 {@link MediaStatus#FINISHED}（转码完成）才签发，否则 4xx。
 */
@Service
public class PlaySignatureService {

    private final MediaMapper mediaMapper;
    private final PlaySignService playSignService;

    public PlaySignatureService(MediaMapper mediaMapper, PlaySignService playSignService) {
        this.mediaMapper = mediaMapper;
        this.playSignService = playSignService;
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

        if (media.getStatus() != MediaStatus.FINISHED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "media not processed yet, current status: " + media.getStatus());
        }

        int experSeconds = Math.max(0, exper);
        long expireAt = playSignService.nowEpoch() + playSignService.ttlSeconds();

        // 规范路径：与 ObjectKeys.hlsPlaylist 一致（无查询串，小写 hex fileId 由调用方保证）
        String path = "/hls/" + fileId + "/index.m3u8";
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
