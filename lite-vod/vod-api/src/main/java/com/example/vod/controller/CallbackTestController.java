package com.example.vod.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地演示用 Webhook 接收端（步骤 13）。
 *
 * <p>Worker 可配置 {@code VOD_CALLBACK_URL=http://localhost:8080/vod/callback/test}，
 * 或 Compose 内 {@code http://vod-api:8080/vod/callback/test}。
 */
@Slf4j
@RestController
@RequestMapping("/vod/callback")
public class CallbackTestController {

    @PostMapping("/test")
    public Map<String, String> receive(@RequestBody Map<String, Object> body) {
        log.info("webhook received: {}", body);
        return Map.of("status", "ok");
    }
}
