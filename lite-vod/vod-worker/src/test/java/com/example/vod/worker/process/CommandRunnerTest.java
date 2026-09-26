package com.example.vod.worker.process;

import com.example.vod.worker.config.CommandProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRunnerTest {

    /** P0/超时等用例关闭白名单，避免依赖把 sleep/sh/cmd 写进生产配置。 */
    private final CommandRunner runner = new CommandRunner(CommandProperties.allowAll());

    @TempDir
    Path workDir;

    @Test
    @Timeout(15)
    void timesOutWhenCommandOutlivesDeadline() throws Exception {
        List<String> command = longRunning(30);
        long start = System.nanoTime();

        CommandTimeoutException ex = assertThrows(CommandTimeoutException.class,
                () -> runner.run(workDir, Duration.ofSeconds(2), command));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 8_000, "timeout should fire before the command finishes, elapsedMs=" + elapsedMs);
        assertEquals(command.get(0), ex.commandName());
        assertEquals(Duration.ofSeconds(2), ex.timeout());
    }

    @Test
    @Timeout(15)
    void timesOutWhenStdoutNeverCloses() throws Exception {
        List<String> command = floodStdout();
        long start = System.nanoTime();

        CommandTimeoutException ex = assertThrows(CommandTimeoutException.class,
                () -> runner.run(workDir, Duration.ofSeconds(2), command));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 8_000, "open stdout must not block the deadline, elapsedMs=" + elapsedMs);
        assertFalse(ex.truncatedOutput().isBlank(), "timeout should keep output already written");
    }

    @Test
    @Timeout(40)
    void destroysProcessOnTimeout() throws Exception {
        Path marker = workDir.resolve("alive.txt");
        List<String> command = writeForever(marker);

        assertThrows(CommandTimeoutException.class,
                () -> runner.run(workDir, Duration.ofSeconds(10), command));

        assertTrue(Files.exists(marker), "child should have written before timeout");
        long size = Files.size(marker);
        Thread.sleep(1200);
        assertEquals(size, Files.size(marker), "child should stop writing after destroyForcibly");
    }

    @Test
    void returnsOutputOnSuccess() throws Exception {
        CommandResult result = runner.run(workDir, Duration.ofSeconds(10), echoHello());

        assertTrue(result.success());
        assertTrue(result.output().contains("hello"));
    }

    @Test
    void returnsNonZeroExitWithoutThrowing() throws Exception {
        CommandResult result = runner.run(workDir, Duration.ofSeconds(10), exitCode(7));

        assertEquals(7, result.exitCode());
        assertFalse(result.success());
    }

    @Test
    @Timeout(20)
    void killsProcessWhenCallerInterrupted() throws Exception {
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                runner.run(workDir, Duration.ofMinutes(2), longRunning(60));
                error.set(new AssertionError("command should have been interrupted"));
            } catch (InterruptedException e) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        Thread.sleep(400);
        worker.interrupt();

        worker.join(10_000);
        assertFalse(worker.isAlive(), "interrupted run should return");
        assertNull(error.get());
        assertTrue(interruptRestored.get(), "interrupt flag should be restored");
    }

    @Test
    void rejectsBinaryOutsideWhitelist() {
        CommandRunner strict = new CommandRunner(CommandProperties.of(List.of("ffmpeg", "ffprobe"), 1024));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> strict.run(workDir, Duration.ofSeconds(5), List.of("bash", "-c", "echo hi")));

        assertTrue(ex.getMessage().contains("binary not allowed"));
        assertTrue(ex.getMessage().contains("bash"));
    }

    @Test
    void rejectsEmptyCommand() {
        CommandRunner strict = new CommandRunner(new CommandProperties());

        assertThrows(IllegalArgumentException.class,
                () -> strict.run(workDir, Duration.ofSeconds(5), List.of()));
    }

    @Test
    void allowsWhitelistedBinaryWithPath() {
        CommandRunner strict = new CommandRunner(CommandProperties.of(List.of("ffmpeg"), 1024));

        // 不真正启动进程：路径里带 ffmpeg 文件名即可通过校验，随后会因找不到可执行文件抛 IOException
        IllegalArgumentException rejected = null;
        try {
            strict.run(workDir, Duration.ofSeconds(1), List.of("/usr/bin/ffmpeg", "-version"));
        } catch (IllegalArgumentException e) {
            rejected = e;
        } catch (Exception ignored) {
            // 预期：白名单通过后由 OS 报找不到文件或真正执行
        }
        assertNull(rejected, "path ending with ffmpeg must pass whitelist");
    }

    @Test
    @Timeout(20)
    void boundsOutputToConfiguredMaxChars() throws Exception {
        int maxChars = 256;
        CommandRunner bounded = new CommandRunner(CommandProperties.of(
                List.of("powershell", "cmd", "sh", "python", "python3"), maxChars));

        CommandResult result = bounded.run(workDir, Duration.ofSeconds(15), printManyXs(2000));

        assertTrue(result.success());
        assertTrue(result.output().length() <= maxChars,
                "output length=" + result.output().length());
        assertTrue(result.output().startsWith(BoundedOutputCollector.TRUNCATION_MARK)
                        || result.output().contains("[truncated]"),
                "bounded output should carry truncation mark");
        assertTrue(result.output().contains("x"), "tail should keep real content");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> longRunning(int seconds) {
        if (windows()) {
            return List.of("ping", "-n", Integer.toString(seconds + 1), "127.0.0.1");
        }
        return List.of("sleep", Integer.toString(seconds));
    }

    private static List<String> floodStdout() {
        if (windows()) {
            return List.of("ping", "-t", "127.0.0.1");
        }
        return List.of("sh", "-c", "while true; do echo x; done");
    }

    private static List<String> writeForever(Path marker) {
        String path = marker.toAbsolutePath().toString();
        if (windows()) {
            String literal = path.replace("'", "''");
            return List.of(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "while ($true) { [IO.File]::AppendAllText('" + literal + "', 'x'); Start-Sleep -Milliseconds 200 }");
        }
        String literal = path.replace("'", "'\\''");
        return List.of("sh", "-c", "while true; do printf x >> '" + literal + "'; sleep 0.2; done");
    }

    private static List<String> echoHello() {
        if (windows()) {
            return List.of("cmd", "/c", "echo", "hello");
        }
        return List.of("echo", "hello");
    }

    private static List<String> exitCode(int code) {
        if (windows()) {
            return List.of("cmd", "/c", "exit", Integer.toString(code));
        }
        return List.of("sh", "-c", "exit " + code);
    }

    /** 打印大量字符，用于验证有界输出截断。 */
    private static List<String> printManyXs(int count) {
        if (windows()) {
            return List.of(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "'x' * " + count);
        }
        return List.of("sh", "-c", "printf %0" + count + "d 0 | tr '0' 'x'");
    }
}
