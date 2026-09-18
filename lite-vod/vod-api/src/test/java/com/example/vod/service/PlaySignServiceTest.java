package com.example.vod.service;

import com.example.vod.config.PlaySignProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定 secret + 固定输入 → 固定 hex，校验算法与实现文档 8.1 逐字一致。
 */
class PlaySignServiceTest {

    private static final String SECRET = "test-secret";
    private static final String PATH = "/hls/f7c2a1b0e9d84f6a/index.m3u8";
    private static final long EXPIRE = 1710003600L;

    private PlaySignService service() {
        return new PlaySignService(new PlaySignProperties(SECRET, "http://localhost", 3600L));
    }

    @Test
    void signShouldMatchGoldenValueForExper300() {
        // 由 openssl/.NET HMAC-SHA256('test-secret', payload) 离线算得
        String expected = "4105d2d8414f573e3c0a0c04f00174ce6cca53d6f881572335d4e0ce12a876f7";
        assertEquals(expected, service().sign(PATH, EXPIRE, 300));
    }

    @Test
    void signShouldMatchGoldenValueForExper0() {
        String expected = "3fd22f4c6d215c9b117459334e9e180971abc0fed4c2100704414b1490899d0c";
        assertEquals(expected, service().sign(PATH, EXPIRE, 0));
    }

    @Test
    void signShouldBeDeterministicAndLowercaseHex() {
        PlaySignService svc = service();
        String a = svc.sign(PATH, EXPIRE, 300);
        String b = svc.sign(PATH, EXPIRE, 300);
        assertEquals(a, b);
        assertTrue(a.matches("[0-9a-f]{64}"), "should be 64-char lowercase hex");
    }

    @Test
    void signShouldChangeWhenExpireChanges() {
        PlaySignService svc = service();
        assertNotEquals(svc.sign(PATH, EXPIRE, 0), svc.sign(PATH, EXPIRE + 1, 0));
    }

    @Test
    void signShouldChangeWhenPathChanges() {
        PlaySignService svc = service();
        assertNotEquals(svc.sign(PATH, EXPIRE, 0),
                svc.sign("/hls/another/index.m3u8", EXPIRE, 0));
    }

    @Test
    void signShouldChangeWhenSecretChanges() {
        String a = service().sign(PATH, EXPIRE, 0);
        String b = new PlaySignService(new PlaySignProperties("other-secret", "http://localhost", 3600L))
                .sign(PATH, EXPIRE, 0);
        assertNotEquals(a, b);
    }

    @Test
    void verifyShouldAcceptValidSignature() {
        PlaySignService svc = service();
        String sign = svc.sign(PATH, EXPIRE, 300);
        assertTrue(svc.verify(PATH, EXPIRE, 300, sign, EXPIRE - 1));
    }

    @Test
    void verifyShouldRejectExpiredSignature() {
        PlaySignService svc = service();
        String sign = svc.sign(PATH, EXPIRE, 300);
        assertFalse(svc.verify(PATH, EXPIRE, 300, sign, EXPIRE));
        assertFalse(svc.verify(PATH, EXPIRE, 300, sign, EXPIRE + 1));
    }

    @Test
    void verifyShouldRejectTamperedSign() {
        PlaySignService svc = service();
        String sign = svc.sign(PATH, EXPIRE, 300);
        String tampered = sign.substring(0, 63) + (sign.charAt(63) == '0' ? "1" : "0");
        assertFalse(svc.verify(PATH, EXPIRE, 300, tampered, EXPIRE - 1));
    }

    @Test
    void verifyShouldRejectEmptySign() {
        assertFalse(service().verify(PATH, EXPIRE, 300, "", EXPIRE - 1));
        assertFalse(service().verify(PATH, EXPIRE, 300, null, EXPIRE - 1));
    }
}
