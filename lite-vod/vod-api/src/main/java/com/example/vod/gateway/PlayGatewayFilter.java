package com.example.vod.gateway;

import com.example.vod.common.config.PreviewProperties;
import com.example.vod.common.domain.media.Media;
import com.example.vod.common.domain.media.MediaMapper;
import com.example.vod.common.storage.MinioStorage;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.service.PlaySignService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 临时播放网关（步骤 11 Spring Filter 方案）：验签后从 MinIO 流式回写 /hls/**。
 *
 * <p>生产建议改为 Nginx auth_request；本 Filter 仅便于开发联调。
 * 正片签发绑定 {@code media_url}（master 或 index）；试看 L2 绑定 {@code preview.m3u8}，
 * 试看 sign 不得访问正片清单或未列入 preview 的 ts。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PlayGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlayGatewayFilter.class);

    private final PlaySignService playSignService;
    private final MinioStorage minioStorage;
    private final MediaMapper mediaMapper;
    private final PreviewProperties previewProperties;

    public PlayGatewayFilter(PlaySignService playSignService,
                             MinioStorage minioStorage,
                             MediaMapper mediaMapper,
                             PreviewProperties previewProperties) {
        this.playSignService = playSignService;
        this.minioStorage = minioStorage;
        this.mediaMapper = mediaMapper;
        this.previewProperties = previewProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/hls/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        addCors(response);
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (!HttpMethod.GET.matches(request.getMethod()) && !HttpMethod.HEAD.matches(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        Optional<PlayPathSupport.HlsRequest> parsed = PlayPathSupport.parse(request.getRequestURI());
        if (parsed.isEmpty()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid hls path");
            return;
        }
        PlayPathSupport.HlsRequest hls = parsed.get();

        String eParam = request.getParameter("e");
        String sign = request.getParameter("sign");
        String experParam = request.getParameter("exper");
        if (eParam == null || eParam.isBlank() || sign == null || sign.isBlank()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "missing e or sign");
            return;
        }

        long expireAt;
        int exper;
        try {
            expireAt = Long.parseLong(eParam);
            exper = experParam == null || experParam.isBlank() ? 0 : Integer.parseInt(experParam);
        } catch (NumberFormatException ex) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid e or exper");
            return;
        }

        Media media = mediaMapper.findByFileId(hls.fileId());
        String mediaUrl = media == null ? null : media.getMediaUrl();
        String fullPath = PlayPathSupport.signedPlaylistPath(hls.fileId(), mediaUrl);
        String previewPath = PlayPathSupport.previewPlaylistPath(hls.fileId());
        long now = playSignService.nowEpoch();

        if (!authorize(hls, fullPath, previewPath, expireAt, exper, sign, now)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid or expired sign");
            return;
        }

        try (InputStream in = minioStorage.openStream(hls.objectKey())) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "object not found");
                return;
            }

            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
            response.setContentType(PlayPathSupport.contentType(hls.relativePath()));

            if (HttpMethod.HEAD.matches(request.getMethod())) {
                return;
            }

            if (PlayPathSupport.isPlaylist(hls.relativePath())) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String query = "e=" + expireAt + "&exper=" + Math.max(0, exper) + "&sign=" + sign;
                byte[] rewritten = HlsPlaylistRewriter.appendQueryToMediaUris(body, query)
                        .getBytes(StandardCharsets.UTF_8);
                response.setContentLength(rewritten.length);
                response.getOutputStream().write(rewritten);
            } else {
                in.transferTo(response.getOutputStream());
            }
            response.flushBuffer();
        } catch (IllegalStateException ex) {
            log.warn("play gateway minio error path={} key={}: {}", hls.requestPath(), hls.objectKey(), ex.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "storage error");
            }
        }
    }

    /**
     * L2 关：一律按正片 path 验签（首期行为）。
     * L2 开：preview 清单绑 preview path；正片清单绑正片 path；ts 先验正片，再试 preview+白名单。
     */
    private boolean authorize(PlayPathSupport.HlsRequest hls,
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

    private static void addCors(HttpServletResponse response) {
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, OPTIONS");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}
