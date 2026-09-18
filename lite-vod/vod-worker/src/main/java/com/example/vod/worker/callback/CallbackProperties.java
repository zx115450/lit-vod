package com.example.vod.worker.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook 回调配置（步骤 13 方案 A）。
 *
 * <p>{@code url} 为空时不发回调，便于本地只跑转码不接业务。
 *
 * @param url              业务回调地址，环境变量 {@code VOD_CALLBACK_URL}
 * @param connectTimeoutMs 连接超时（毫秒）
 * @param readTimeoutMs    读超时（毫秒）
 * @param maxAttempts      单次通知最大尝试次数（含首次），建议 2～3
 * @param poolCoreSize     回调线程池核心线程数
 * @param poolMaxSize      回调线程池最大线程数
 * @param queueCapacity    有界队列容量，满则拒绝并打日志（不阻塞消费线程）
 * @param keepAliveSeconds 非核心线程空闲回收秒数
 */
@ConfigurationProperties(prefix = "vod.callback")
public record CallbackProperties(
        String url,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxAttempts,
        int poolCoreSize,
        int poolMaxSize,
        int queueCapacity,
        int keepAliveSeconds
) {
    public CallbackProperties() {
        this("", 2000, 3000, 3, 1, 2, 200, 60);
    }

    /** 测试 / 手工构造时补齐线程池默认值。 */
    public static CallbackProperties of(String url, int connectTimeoutMs, int readTimeoutMs, int maxAttempts) {
        return new CallbackProperties(url, connectTimeoutMs, readTimeoutMs, maxAttempts, 1, 2, 200, 60);
    }

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs <= 0 ? 2000 : connectTimeoutMs;
    }

    public int readTimeoutMs() {
        return readTimeoutMs <= 0 ? 3000 : readTimeoutMs;
    }

    public int maxAttempts() {
        return maxAttempts <= 0 ? 3 : maxAttempts;
    }

    public int poolCoreSize() {
        return poolCoreSize <= 0 ? 1 : poolCoreSize;
    }

    public int poolMaxSize() {
        int max = poolMaxSize <= 0 ? 2 : poolMaxSize;
        return Math.max(max, poolCoreSize());
    }

    public int queueCapacity() {
        return queueCapacity <= 0 ? 200 : queueCapacity;
    }

    public int keepAliveSeconds() {
        return keepAliveSeconds <= 0 ? 60 : keepAliveSeconds;
    }
}
