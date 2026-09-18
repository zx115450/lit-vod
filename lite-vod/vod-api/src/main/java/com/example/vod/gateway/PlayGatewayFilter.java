package com.example.vod.gateway;

import com.example.vod.common.storage.MinioStorage;
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
import java.util.Optional;

/**
 * 临时播放网关（步骤 11 Spring Filter 方案）：验签后从 MinIO 流式回写 /hls/**。
 *
 * <p>生产建议改为 Nginx auth_request；本 Filter 仅便于开发联调。
 * 签发仍绑定 {@code /hls/{fileId}/index.m3u8}，目录下任意资源用同一套 query 验签。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PlayGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PlayGatewayFilter.class);

    private final PlaySignService playSignService;
    private final MinioStorage minioStorage;

    public PlayGatewayFilter(PlaySignService playSignService, MinioStorage minioStorage) {
        this.playSignService = playSignService;
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

        if (!playSignService.verify(hls.signedPath(), expireAt, exper, sign, playSignService.nowEpoch())) {
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

    private static void addCors(HttpServletResponse response) {
        // 签名在 query，演示页可与 API 不同源；不配 cookie，可用 *
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, OPTIONS");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}
