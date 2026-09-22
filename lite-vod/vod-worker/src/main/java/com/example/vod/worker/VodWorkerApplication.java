package com.example.vod.worker;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.worker.callback.CallbackProperties;
import com.example.vod.worker.config.WorkerProperties;
import com.example.vod.worker.process.CommandRunner;
import com.example.vod.worker.process.CommandResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Worker 主类。
 *
 * <p>scanBasePackages 同时包含 worker 自身与 common 模块：
 * common 的 @Configuration（MinioConfig / RabbitConfig）与 @Component（MinioStorage）
 * 在 com.example.vod.common 下，不在 worker 主包 com.example.vod.worker 范围内，
 * 必须显式加入扫描，否则这些 bean 不会被注册。
 *
 * <p>@MapperScan 显式指向 common 的 Mapper 包，避免依赖自动扫描的隐式行为。
 */
@Slf4j
@SpringBootApplication(scanBasePackages = {"com.example.vod.worker", "com.example.vod.common"})
@EnableConfigurationProperties({WorkerProperties.class, CallbackProperties.class, AbrProperties.class})
@MapperScan("com.example.vod.common.domain.media")
public class VodWorkerApplication {

    private final CommandRunner commandRunner;

    public VodWorkerApplication(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    public static void main(String[] args) {
        SpringApplication.run(VodWorkerApplication.class, args);
    }

    /**
     * 启动时跑一次 ffmpeg / ffprobe -version，比等到第一条消息再失败更早暴露环境问题。
     * 本机未装 FFmpeg 时启动即失败，符合 docs/Java-ProcessBuilder.md 的建议。
     */
    @PostConstruct
    public void onStart() {
        verifyCommand("ffmpeg", List.of("ffmpeg", "-version"));
        verifyCommand("ffprobe", List.of("ffprobe", "-version"));
        log.info("worker started, ffmpeg & ffprobe verified");
    }

    private void verifyCommand(String name, List<String> command) {
        try {
            CommandResult r = commandRunner.run(Path.of("."), Duration.ofSeconds(10), command);
            if (!r.success()) {
                throw new IllegalStateException(name + " -version exit=" + r.exitCode());
            }
            log.info("{} available: {}", name, firstLine(r.output()));
        } catch (Exception e) {
            // 启动期找不到 ffmpeg 直接抛出，让容器进入重启循环
            throw new IllegalStateException("verify " + name + " failed, please install ffmpeg in worker image", e);
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        int n = s.indexOf('\n');
        return n < 0 ? s.trim() : s.substring(0, n).trim();
    }
}
