package com.example.vod.worker.process;

import java.time.Duration;
import java.util.List;

/**
 * 子进程超时异常。{@link #truncatedOutput} 已截断到 512 字符，可直接写入 error_msg。
 */
public class CommandTimeoutException extends RuntimeException {

    private final String commandName;
    private final Duration timeout;
    private final String truncatedOutput;

    public CommandTimeoutException(String commandName, Duration timeout, String truncatedOutput) {
        super("command timeout: " + commandName + " after " + timeout.toMillis() + "ms");
        this.commandName = commandName;
        this.timeout = timeout;
        this.truncatedOutput = truncatedOutput;
    }

    public String commandName() {
        return commandName;
    }

    public Duration timeout() {
        return timeout;
    }

    public String truncatedOutput() {
        return truncatedOutput;
    }

    /**
     * 便于在 Consumer 里复用同一份截断逻辑。
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max);
    }

    public static String truncate512(String s) {
        return truncate(s, 512);
    }

    /**
     * 取命令名用于日志/异常信息。
     */
    public static String nameOf(List<String> command) {
        return command == null || command.isEmpty() ? "?" : command.get(0);
    }
}
