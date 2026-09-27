package com.example.vod.gateway;

import com.example.vod.common.storage.MinioStorage;
import com.example.vod.service.PlayAuthService;
import com.example.vod.service.PlayPlaylistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 临时播放网关（步骤 11 Spring Filter 方案）：验签后从 MinIO 流式回写 /hls/**。
 *
 * <p>生产建议改为 Nginx auth_request；本 Filter 仅便于开发联调。
 * 可通过 {@code play-sign.gateway-filter-enabled=false} 关闭，改走网关 + {@code /internal/play-auth}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "play-sign", name = "gateway-filter-enabled", havingValue = "true", matchIfMissing = true)
public class PlayGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlayGatewayFilter.class);

    private final PlayAuthService playAuthService;
    private final PlayPlaylistService playPlaylistService;
    private final MinioStorage minioStorage;

    public PlayGatewayFilter(PlayAuthService playAuthService,
                             PlayPlaylistService playPlaylistService,
                             MinioStorage minioStorage) {
        this.playAuthService = playAuthService;
        this.playPlaylistService = playPlaylistService;
        this.minioStorage = minioStorage;
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

        String e = request.getParameter("e");
        String sign = request.getParameter("sign");
        String exper = request.getParameter("exper");
        if (!playAuthService.authorize(hls, e, sign, exper)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid or expired sign");
            return;
        }

        if (PlayPathSupport.isPlaylist(hls.relativePath())) {
            try {
                Optional<PlayPlaylistService.RewrittenPlaylist> loaded =
                        playPlaylistService.loadRewritten(hls, e, sign, exper);
                if (loaded.isEmpty()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "object not found");
                    return;
                }
                PlayPlaylistService.RewrittenPlaylist pl = loaded.get();
                response.setStatus(HttpServletResponse.SC_OK);
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
                response.setContentType(pl.contentType());
                if (HttpMethod.HEAD.matches(request.getMethod())) {
                    return;
                }
                response.setContentLength(pl.body().length);
                response.getOutputStream().write(pl.body());
                response.flushBuffer();
            } catch (IllegalStateException ex) {
                log.warn("play gateway playlist error path={}: {}", hls.requestPath(), ex.getMessage());
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "storage error");
                }
            }
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

            in.transferTo(response.getOutputStream());
            response.flushBuffer();
        } catch (IllegalStateException ex) {
            log.warn("play gateway minio error path={} key={}: {}", hls.requestPath(), hls.objectKey(), ex.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "storage error");
            }
        }
    }

    private static void addCors(HttpServletResponse response) {
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, OPTIONS");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}
