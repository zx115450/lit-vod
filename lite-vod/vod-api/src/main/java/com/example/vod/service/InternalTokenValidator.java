package com.example.vod.service;

import com.example.vod.config.InternalProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 校验 {@code X-Internal-Token}（书城 BFF ↔ vod-api 共享密钥）。
 */
@Component
public class InternalTokenValidator {

    public static final String HEADER = "X-Internal-Token";

    private final InternalProperties props;

    public InternalTokenValidator(InternalProperties props) {
        this.props = props;
    }

    public void requireValid(String provided) {
        if (!props.enabled()) {
            return;
        }
        String expected = props.token();
        if (expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "internal token not configured");
        }
        if (provided == null || provided.isBlank() || !constantTimeEquals(expected, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "invalid or missing " + HEADER);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
