package com.example.vod.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 转码相关参数。
 *
 * @param tempDir        本地工作目录前缀，转码产物落在 {tempDir}/{fileId}/
 * @param maxAttempts    最大重试次数（含首次），超过则 ack 停止，避免毒消息死循环
 * @param maxDurationSec 时长上限（秒），超过直接 FAILED 不重试；6 小时 = 21600
 * @param concurrency   每个 Worker 容器消费并发数，1~2 避免拖垮同机 API
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        String tempDir,
        int maxAttempts,
        long maxDurationSec,
        int concurrency
) {
    public WorkerProperties() {
        this("/tmp/vod", 3, 6L * 3600, 1);
    }

    public String tempDir() {
        return tempDir == null || tempDir.isBlank() ? "/tmp/vod" : tempDir;
    }

    public int maxAttempts() {
        return maxAttempts <= 0 ? 3 : maxAttempts;
    }

    public long maxDurationSec() {
        return maxDurationSec <= 0 ? 6L * 3600 : maxDurationSec;
    }

    public int concurrency() {
        return concurrency <= 0 ? 1 : concurrency;
    }
}
