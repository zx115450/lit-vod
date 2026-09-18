package com.example.vod.worker.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link ProcessBuilder} 调外部命令的最小封装。
 *
 * <p>要点（详见 docs/Java-ProcessBuilder.md）：
 * <ul>
 *   <li>参数以 List 形式传入，每个 token 一个元素，避免 shell 拼接与转义</li>
 *   <li>{@code redirectErrorStream(true)} 合并 stderr 到 stdout，再一次性读完，避免管道死锁</li>
 *   <li>{@code waitFor(timeout, unit)} 限时等待，超时 {@code destroyForcibly} 杀进程</li>
 *   <li>被中断时也要杀子进程，并恢复中断标志</li>
 * </ul>
 */
@Slf4j
@Component
public class CommandRunner {

    /**
     * 执行命令，返回退出码与合并后的输出。
     *
     * @param workDir 工作目录，命令里的相对路径相对它解析
     * @param timeout 等待上限，超时杀进程并抛 {@link CommandTimeoutException}
     * @param command 命令与参数列表，第一个元素是可执行文件
     */
    public CommandResult run(java.nio.file.Path workDir, Duration timeout, List<String> command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        log.debug("run cmd: {} (workDir={})", command, workDir);
        Process process = pb.start();

        // 必须在 waitFor 之前把合并流读完，否则管道满后子进程会卡住
        String output;
        try {
            output = readFully(process.getInputStream());
        } catch (IOException e) {
            process.destroyForcibly();
            throw e;
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String summary = CommandTimeoutException.truncate512(output);
            throw new CommandTimeoutException(CommandTimeoutException.nameOf(command), timeout, summary);
        }

        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("cmd failed exit={}: {}", exit, CommandTimeoutException.truncate512(output));
        }
        return new CommandResult(exit, output);
    }

    private static String readFully(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
