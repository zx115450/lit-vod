package com.example.vod.service;

import com.example.vod.config.PlaySignProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 播放 URL 的 HMAC-SHA256 签名与验签，对应实现文档 8.1 / 步骤 10。
 *
 * <pre>
 * 待签名字符串：path={uriPath}&e={expireEpoch}&exper={experSeconds|0}
 * sign = Hex(HMAC_SHA256(secret, 待签名字符串))
 * </pre>
 *
 * <p>签发与验签必须用同一套算法、同一 secret、同一十六进制大小写（统一小写）。
 * 本类同时供第 11 步网关 / 内部验签接口复用。
 */
@Service
public class PlaySignService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final PlaySignProperties props;

    public PlaySignService(PlaySignProperties props) {
        this.props = props;
    }

    /**
     * 按 path + 过期时间 + 试看秒数生成签名（小写十六进制）。
     */
    public String sign(String path, long expireAt, int exper) {
        String payload = buildPayload(path, expireAt, exper);
        return hex(hmac(payload));
    }

    /**
     * 验签：重算签名并恒等比较（防时序攻击），同时校验未过期。
     *
     * @param now 当前时间戳（秒），由调用方传入便于测试
     * @return true 合法且未过期
     */
    public boolean verify(String path, long expireAt, int exper, String sign, long now) {
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        if (now >= expireAt) {
            return false;
        }
        String expected = sign(path, expireAt, exper);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }

    /** 便于调用方取当前时间，集中一点便于测试替换。 */
    public long nowEpoch() {
        return Instant.now().getEpochSecond();
    }

    public long ttlSeconds() {
        return props.ttlSeconds();
    }

    public String publicBase() {
        return props.publicBase();
    }

    private String buildPayload(String path, long expireAt, int exper) {
        return "path=" + path + "&e=" + expireAt + "&exper=" + Math.max(0, exper);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("hmac sign failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
