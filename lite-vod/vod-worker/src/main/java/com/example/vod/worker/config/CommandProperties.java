package com.example.vod.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外部命令执行策略：可执行白名单与输出上限。
 *
 * <pre>
 * worker:
 *   command:
 *     whitelist-enabled: true
 *     allowed-binaries:
 *       - ffmpeg
 *       - ffprobe
 *     max-output-chars: 65536
 * </pre>
 */
@ConfigurationProperties(prefix = "worker.command")
public final class CommandProperties {

    public static final int DEFAULT_MAX_OUTPUT_CHARS = 65_536;
    public static final List<String> DEFAULT_ALLOWED = List.of("ffmpeg", "ffprobe");

    private final boolean whitelistEnabled;
    private final List<String> allowedBinaries;
    private final int maxOutputChars;
    /** 构造时规范化好的白名单，供 {@link #isAllowed} 直接 contains。 */
    private final Set<String> allowedNames;

    public CommandProperties() {
        this(true, DEFAULT_ALLOWED, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public CommandProperties(boolean whitelistEnabled, List<String> allowedBinaries, int maxOutputChars) {
        this.whitelistEnabled = whitelistEnabled;
        List<String> binaries = (allowedBinaries == null || allowedBinaries.isEmpty())
                ? DEFAULT_ALLOWED
                : List.copyOf(allowedBinaries);
        this.allowedBinaries = binaries;
        this.maxOutputChars = maxOutputChars <= 0 ? DEFAULT_MAX_OUTPUT_CHARS : maxOutputChars;
        this.allowedNames = binaries.stream()
                .map(CommandProperties::binaryName)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 单测用：关闭白名单，保留默认输出上限。 */
    public static CommandProperties allowAll() {
        return new CommandProperties(false, DEFAULT_ALLOWED, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** 单测用：自定义白名单与输出上限。 */
    public static CommandProperties of(List<String> allowedBinaries, int maxOutputChars) {
        return new CommandProperties(true, allowedBinaries, maxOutputChars);
    }

    public boolean whitelistEnabled() {
        return whitelistEnabled;
    }

    public List<String> allowedBinaries() {
        return allowedBinaries;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    /**
     * 取可执行文件名（去掉路径与 Windows 后缀），用于白名单比较。
     */
    public static String binaryName(String executable) {
        if (executable == null || executable.isBlank()) {
            return "";
        }
        String name = Path.of(executable).getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    public boolean isAllowed(String executable) {
        if (!whitelistEnabled) {
            return true;
        }
        return allowedNames.contains(binaryName(executable).toLowerCase(Locale.ROOT));
    }
}
