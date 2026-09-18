package com.example.vod.worker.ffmpeg;

/**
 * FFmpeg / ffprobe 执行失败的统一异常。
 * {@link #truncatedOutput} 已截断到 512 字符，可直接写入 error_msg。
 */
public class TranscodeException extends RuntimeException {

    private final String truncatedOutput;

    public TranscodeException(String message, String truncatedOutput) {
        super(message);
        this.truncatedOutput = truncatedOutput;
    }

    public TranscodeException(String message, String truncatedOutput, Throwable cause) {
        super(message, cause);
        this.truncatedOutput = truncatedOutput;
    }

    public String truncatedOutput() {
        return truncatedOutput;
    }
}
