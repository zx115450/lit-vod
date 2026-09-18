package com.example.vod.worker.callback;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 转码终态 Webhook 通知：异步 POST，短超时 + 有限重试。
 *
 * <p>线程池按生产习惯显式构造：有界队列 + 命名非 daemon 线程 + 拒绝时打日志丢弃
 *（避免 CallerRuns 回堵到 MQ 消费线程，也不使用 Executors 无界队列）。
 *
 * <p>失败只打日志，不影响媒资 / task 已落库的状态；也不阻塞临时目录清理。
 */
@Slf4j
@Component
public class CallbackNotifier {

    private static final long RETRY_BACKOFF_MS = 500L;

    private final CallbackProperties props;
    private final RestClient restClient;
    private final ThreadPoolExecutor executor;

    public CallbackNotifier(CallbackProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.executor = new ThreadPoolExecutor(
                props.poolCoreSize(),
                props.poolMaxSize(),
                props.keepAliveSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(props.queueCapacity()),
                namedThreadFactory("vod-callback"),
                (r, pool) -> log.error(
                        "callback rejected (queue full): poolSize={} active={} queue={}",
                        pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size())
        );
        // 允许核心线程在 keepAlive 后回收，闲时不占线程
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 异步投递；未配置 URL 时直接跳过。
     */
    public void notifyAsync(CallbackPayload payload) {
        if (!props.enabled()) {
            log.debug("callback skipped (VOD_CALLBACK_URL empty): fileId={} status={}",
                    payload.fileId(), payload.status());
            return;
        }
        try {
            executor.execute(() -> postWithRetry(payload));
        } catch (Exception e) {
            // 防御：自定义 RejectedExecutionHandler 一般不抛；若换策略仍保证不炸消费线程
            log.error("callback submit failed fileId={} status={}: {}",
                    payload.fileId(), payload.status(), e.toString());
        }
    }

    private void postWithRetry(CallbackPayload payload) {
        int max = props.maxAttempts();
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                restClient.post()
                        .uri(props.url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                log.info("callback ok fileId={} status={} attempt={}/{}",
                        payload.fileId(), payload.status(), attempt, max);
                return;
            } catch (Exception e) {
                log.warn("callback failed fileId={} status={} attempt={}/{}: {}",
                        payload.fileId(), payload.status(), attempt, max, e.toString());
                if (attempt < max) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
                }
            }
        }
        log.error("callback abandoned after {} attempts: fileId={} status={}",
                max, payload.fileId(), payload.status());
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("uncaught in {}: {}", thread.getName(), ex.toString(), ex));
            return t;
        };
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
