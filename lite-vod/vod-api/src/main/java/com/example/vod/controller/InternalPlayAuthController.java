package com.example.vod.controller;

import com.example.vod.service.PlayAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nginx {@code auth_request} 旁路验签：只返回 200/403，不吐媒体。
 *
 * <p>期望请求头 {@code X-Original-URI} 为完整 path+query（见 Nginx {@code $request_uri}）。
 */
@RestController
@RequestMapping("/internal")
public class InternalPlayAuthController {

    private final PlayAuthService playAuthService;

    public InternalPlayAuthController(PlayAuthService playAuthService) {
        this.playAuthService = playAuthService;
    }

    @GetMapping("/play-auth")
    public ResponseEntity<Void> playAuth(
            @RequestHeader(value = "X-Original-URI", required = false) String originalUri) {
        if (playAuthService.authorizeOriginalUri(originalUri)) {
            return ResponseEntity.ok(). build();
        }
        return ResponseEntity.status(403).build();
    }
}
