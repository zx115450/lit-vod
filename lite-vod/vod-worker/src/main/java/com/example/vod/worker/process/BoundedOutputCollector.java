package com.example.vod.worker.process;

/**
 * 有界输出缓冲：只保留尾部 {@code maxChars} 个字符，超限时丢弃头部并加截断标记。
 *
 * <p>供 {@link CommandRunner} 旁路读流使用，避免长转码日志撑满堆内存。
 */
public final class BoundedOutputCollector {

    static final String TRUNCATION_MARK = "...[truncated]...\n";

    private final int maxChars;
    private final StringBuilder buf = new StringBuilder();
    private boolean truncated;

    public BoundedOutputCollector(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
    }

    public synchronized void append(CharSequence chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buf.append(chunk);
        trimToLimit();
    }

    public synchronized void append(byte[] bytes, int off, int len, java.nio.charset.Charset charset) {
        if (len <= 0) {
            return;
        }
        append(new String(bytes, off, len, charset));
    }

    public synchronized String snapshot() {
        return buf.toString();
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public synchronized int length() {
        return buf.length();
    }

    private void trimToLimit() {
        if (buf.length() <= maxChars) {
            return;
        }
        truncated = true;
        int markLen = TRUNCATION_MARK.length();
        int keep = Math.max(0, maxChars - markLen);
        String tail = buf.substring(buf.length() - keep);
        buf.setLength(0);
        buf.append(TRUNCATION_MARK).append(tail);
    }
}
