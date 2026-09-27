package com.example.vod.service;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.gateway.HlsPlaylistMediaUris;
import com.example.vod.gateway.PlayPathSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 播放验签（Filter 与 Nginx auth_request 共用），不读媒体字节回写。
 *
 * <p>规则与历史 {@code PlayGatewayFilter} 一致：正片 / 试看 L2 白名单、过期与 HMAC 恒等比较。
 */
@Service
public class PlayAuthService {

    private static final Logger log = LoggerFactory.getLogger(PlayAuthService.class);

    private final PlaySignService playSignService;
    private final MediaMapper mediaMapper;
    private final MinioStorage minioStorage;
    private final PreviewProperties previewProperties;

    public PlayAuthService(PlaySignService playSignService,
                           MediaMapper mediaMapper,
                           MinioStorage minioStorage,
                           PreviewProperties previewProperties) {
        this.playSignService = playSignService;
        this.mediaMapper = mediaMapper;
        this.minioStorage = minioStorage;
        this.previewProperties = previewProperties;
    }

    /**
     * 供 {@code auth_request}：从完整 URI（path + query）验签。
     *
     * @param originalUri 例如 {@code /hls/{fileId}/index.m3u8?e=...&exper=0&sign=...}
     */
    public boolean authorizeOriginalUri(String originalUri) {
        if (originalUri == null || originalUri.isBlank()) {
            return false;
        }
        Optional<PlayPathSupport.HlsRequest> parsed = PlayPathSupport.parse(originalUri);
        if (parsed.isEmpty()) {
            return false;
        }
        Map<String, String> query = parseQuery(originalUri);
        return authorize(parsed.get(), query.get("e"), query.get("sign"), query.get("exper"));
    }

    /**
     * 供 Filter：已拆好的 path 与 query 参数。
     */
    public boolean authorize(PlayPathSupport.HlsRequest hls, String eParam, String sign, String experParam) {
        if (hls == null) {
            return false;
        }
        if (eParam == null || eParam.isBlank() || sign == null || sign.isBlank()) {
            return false;
        }
        long expireAt;
        int exper;
        try {
            expireAt = Long.parseLong(eParam);
            exper = experParam == null || experParam.isBlank() ? 0 : Integer.parseInt(experParam);
        } catch (NumberFormatException ex) {
            return false;
        }
        return authorize(hls, expireAt, exper, sign);
    }

    private boolean authorize(PlayPathSupport.HlsRequest hls, long expireAt, int exper, String sign) {
        Media media = mediaMapper.findByFileId(hls.fileId());
        String mediaUrl = media == null ? null : media.getMediaUrl();
        String fullPath = PlayPathSupport.signedPlaylistPath(hls.fileId(), mediaUrl);
        String previewPath = PlayPathSupport.previewPlaylistPath(hls.fileId());
        long now = playSignService.nowEpoch();
        return authorize(hls, fullPath, previewPath, expireAt, exper, sign, now);
    }

    /**
     * L2 关：一律按正片 path 验签。
     * L2 开：preview 清单绑 preview path；正片清单绑正片 path；ts 先验正片，再试 preview+白名单。
     */
    boolean authorize(PlayPathSupport.HlsRequest hls,
                      String fullPath,
                      String previewPath,
                      long expireAt,
                      int exper,
                      String sign,
                      long now) {
        if (!previewProperties.l2Enabled()) {
            return playSignService.verify(fullPath, expireAt, exper, sign, now);
        }

        String relative = hls.relativePath();
        if (PlayPathSupport.isPreviewPlaylist(relative)) {
            return playSignService.verify(previewPath, expireAt, exper, sign, now);
        }
        if (PlayPathSupport.isPlaylist(relative)) {
            return playSignService.verify(fullPath, expireAt, exper, sign, now);
        }
        if (isTs(relative)) {
            if (playSignService.verify(fullPath, expireAt, exper, sign, now)) {
                return true;
            }
            if (!playSignService.verify(previewPath, expireAt, exper, sign, now)) {
                return false;
            }
            return isListedInPreview(hls.fileId(), relative);
        }
        return playSignService.verify(fullPath, expireAt, exper, sign, now);
    }

    private boolean isListedInPreview(String fileId, String relativePath) {
        try (InputStream in = minioStorage.openStream(ObjectKeys.hlsPreview(fileId))) {
            if (in == null) {
                return false;
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> uris = HlsPlaylistMediaUris.parse(body);
            return HlsPlaylistMediaUris.contains(uris, relativePath);
        } catch (Exception e) {
            log.warn("read preview playlist failed fileId={}: {}", fileId, e.toString());
            return false;
        }
    }

    private static boolean isTs(String relativePath) {
        return relativePath != null && relativePath.toLowerCase(Locale.ROOT).endsWith(".ts");
    }

    /** 解析 {@code path?a=1&b=2}；重复 key 取首次。值不做 URL decode（sign 为 hex）。 */
    static Map<String, String> parseQuery(String uri) {
        Map<String, String> map = new LinkedHashMap<>();
        int q = uri.indexOf('?');
        if (q < 0 || q == uri.length() - 1) {
            return map;
        }
        String query = uri.substring(q + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            if (!key.isEmpty() && !map.containsKey(key)) {
                map.put(key, value);
            }
        }
        return map;
    }
}
