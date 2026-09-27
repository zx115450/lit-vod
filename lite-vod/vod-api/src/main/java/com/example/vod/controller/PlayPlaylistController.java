package com.example.vod.controller;

import com.example.vod.gateway.PlayPathSupport;
import com.example.vod.service.PlayAuthService;
import com.example.vod.service.PlayPlaylistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Nginx 网关模式下的 m3u8 改写出口（Filter 关闭后承接 /hls/ 下的清单请求）。
 *
 * <p>验签后从 MinIO 读清单并补齐 ts 的签名 query；不回写 .ts 大流量。
 */
@RestController
@RequestMapping
public class PlayPlaylistController {

    private final PlayAuthService playAuthService;
    private final PlayPlaylistService playPlaylistService;

    public PlayPlaylistController(PlayAuthService playAuthService, PlayPlaylistService playPlaylistService) {
        this.playAuthService = playAuthService;
        this.playPlaylistService = playPlaylistService;
    }

    @GetMapping("/hls/**")
    public ResponseEntity<byte[]> playlist(HttpServletRequest request) {
        Optional<PlayPathSupport.HlsRequest> parsed = PlayPathSupport.parse(request.getRequestURI());
        if (parsed.isEmpty() || !PlayPathSupport.isPlaylist(parsed.get().relativePath())) {
            return ResponseEntity.notFound().build();
        }
        PlayPathSupport.HlsRequest hls = parsed.get();
        String e = request.getParameter("e");
        String sign = request.getParameter("sign");
        String exper = request.getParameter("exper");
        if (!playAuthService.authorize(hls, e, sign, exper)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            Optional<PlayPlaylistService.RewrittenPlaylist> loaded =
                    playPlaylistService.loadRewritten(hls, e, sign, exper);
            if (loaded.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            PlayPlaylistService.RewrittenPlaylist pl = loaded.get();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                    .contentType(MediaType.parseMediaType(pl.contentType()))
                    .body(pl.body());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
