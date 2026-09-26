package com.example.vod.worker.process;

import com.example.vod.worker.config.CommandProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link ProcessBuilder} 调外部命令的最小封装。
 *
 * <p>要点（详见 docs/Java-ProcessBuilder.md、docs/06-工程化实践记录/06-CommandRunner增强实现.md）：
 * <ul>
 *   <li>参数以 List 形式传入，每个 token 一个元素，避免 shell 拼接与转义</li>
 *   <li>可执行白名单：默认仅 {@code ffmpeg}/{@code ffprobe}（{@link CommandProperties}）</li>
 *   <li>{@code redirectErrorStream(true)} 合并 stderr 到 stdout，由旁路线程有界排空</li>
 *   <li>主线程 {@code waitFor(timeout)} 限时等待；超时 {@code destroyForcibly} 杀进程</li>
 *   <li>被中断时也要杀子进程，并恢复中断标志</li>
 * </ul>
 */
@Slf4j
@Component
public class CommandRunner {

    /** 超时或中断后，等待子进程真正退出的宽限。 */
    private static final Duration DESTROY_GRACE = Duration.ofSeconds(5);

    /** 进程结束后等待读流线程收尾，避免线程泄漏。 */
    private static final long READER_JOIN_MS = 5_000L;

    private final CommandProperties commandProperties;

    public CommandRunner(CommandProperties commandProperties) {
        this.commandProperties = commandProperties == null
                ? new CommandProperties()
                : commandProperties;
    }

    /**
     * 执行命令，返回退出码与合并后的（有界）输出。
     *
     * @param workDir 工作目录，命令里的相对路径相对它解析
     * @param timeout 等待上限，超时杀进程并抛 {@link CommandTimeoutException}
     * @param command 命令与参数列表，第一个元素是可执行文件
     * @throws IllegalArgumentException 命令为空或不在白名单内
     */
    public CommandResult run(Path workDir, Duration timeout, List<String> command)
            throws IOException, InterruptedException {

        assertAllowed(command);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        log.debug("run cmd: {} (workDir={})", command, workDir);
        Process process = pb.start();

        OutputReader reader = new OutputReader(
                process.getInputStream(), commandProperties.maxOutputChars());
        Thread readerThread = new Thread(reader, "vod-cmd-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean timedOut = false;
        InterruptedException interrupted = null;
        try {
            // 读流在旁路，这里的等待不会被「stdout 未关闭」拖住
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                destroy(process, command);
            }
        } catch (InterruptedException e) {
            interrupted = e;
            destroy(process, command);
        } finally {
            joinReader(readerThread);
        }

        if (reader.error() != null && interrupted == null && !timedOut) {
            log.warn("read command output failed: {}", reader.error().toString());
        }
        String output = reader.output();

        if (interrupted != null) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (timedOut) {
            throw new CommandTimeoutException(
                    CommandTimeoutException.nameOf(command),
                    timeout,
                    CommandTimeoutException.truncate512(output));
        }

        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("cmd failed exit={}: {}", exit, CommandTimeoutException.truncate512(output));
        }
        return new CommandResult(exit, output);
    }

    private void assertAllowed(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        String executable = command.get(0);
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("command executable must not be blank");
        }
        if (!commandProperties.isAllowed(executable)) {
            String name = CommandProperties.binaryName(executable);
            throw new IllegalArgumentException(
                    "binary not allowed: " + name + " (allowed=" + commandProperties.allowedBinaries() + ")");
        }
    }

    private static void destroy(Process process, List<String> command) {
        process.destroyForcibly();
        try {
            if (!process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("process still alive after destroyForcibly: {}",
                        CommandTimeoutException.nameOf(command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 主线程在拿结果之前，限时等旁路读流线程收尾，同时正确处理中断标志。 */
    private static void joinReader(Thread readerThread) {
        boolean interrupted = Thread.interrupted();
        try {
            readerThread.join(READER_JOIN_MS);
            if (readerThread.isAlive()) {
                log.warn("command output reader did not finish in {}ms", READER_JOIN_MS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 旁路排空合并流到有界缓冲。进程被强杀时保留已读到的尾部，供超时异常带上日志。
     */
    private static final class OutputReader implements Runnable {

        private final InputStream in;
        private final BoundedOutputCollector collector;
        private volatile IOException error;

        private OutputReader(InputStream in, int maxChars) {
            this.in = in;
            this.collector = new BoundedOutputCollector(maxChars);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    collector.append(chunk, 0, n, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                error = e;
            }
        }

        private String output() {
            return collector.snapshot();
        }

        private IOException error() {
            return error;
        }
    }
}
