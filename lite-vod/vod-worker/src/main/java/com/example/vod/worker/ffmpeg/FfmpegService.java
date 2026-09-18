package com.example.vod.worker.ffmpeg;

import com.example.vod.worker.process.CommandResult;
import com.example.vod.worker.process.CommandRunner;
import com.example.vod.worker.process.CommandTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 封装步骤 09 的三条命令：HLS 转码、截封面、读时长。
 *
 * <p>所有命令的工作目录统一为 {@code /tmp/vod/{fileId}/}，命令里用相对文件名。
 * 命令本身与参数含义见 docs/05-分步实现指南/09-Worker转码闭环.md。
 */
@Slf4j
@Service
public class FfmpegService {

    /** 单码率 720p HLS。注意 scale=-2:720 是一个参数。 */
    private static final List<String> HLS_CMD = List.of(
            "ffmpeg", "-y", "-i", "source.mp4",
            "-vf", "scale=-2:720",
            "-c:v", "libx264", "-preset", "medium", "-crf", "23",
            "-c:a", "aac", "-b:a", "128k",
            "-hls_time", "6", "-hls_list_size", "0",
            "-hls_segment_filename", "segment_%03d.ts",
            "-f", "hls", "index.m3u8");

    /** 截封面，第 3 秒。片长短于 3 秒时改用 0 秒（由调用方判断后传 startTime）。 */
    private static List<String> coverCmd(String startTime) {
        return List.of(
                "ffmpeg", "-y", "-ss", startTime, "-i", "source.mp4",
                "-vframes", "1", "-q:v", "2", "cover.jpg");
    }

    /** 读时长，秒数写到 stdout。 */
    private static final List<String> PROBE_CMD = List.of(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            "source.mp4");

    private static final Duration HLS_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration COVER_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final CommandRunner runner;

    public FfmpegService(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * 转码为 HLS，写出 index.m3u8 与 segment_*.ts。
     */
    public void transcodeHls(Path workDir) {
        CommandResult r;
        try {
            r = runner.run(workDir, HLS_TIMEOUT, HLS_CMD);
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffmpeg hls timeout", e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffmpeg hls io error", "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffmpeg hls failed exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }
        log.info("hls done: {}", workDir);
    }

    /**
     * 截封面。片长短于 3 秒时调用方应传 "00:00:00"。
     */
    public void captureCover(Path workDir, String startTime) {
        CommandResult r;
        try {
            r = runner.run(workDir, COVER_TIMEOUT, coverCmd(startTime));
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffmpeg cover timeout", e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffmpeg cover io error", "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffmpeg cover failed exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }
        log.info("cover done: {}", workDir);
    }

    /**
     * 读时长（秒）。失败抛 {@link TranscodeException}。
     */
    public double probeDuration(Path workDir) {
        CommandResult r;
        try {
            r = runner.run(workDir, PROBE_TIMEOUT, PROBE_CMD);
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffprobe timeout", e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffprobe io error", "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffprobe failed exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }
        try {
            return Double.parseDouble(r.output().trim());
        } catch (NumberFormatException e) {
            throw new TranscodeException("ffprobe parse failed: " + r.output(),
                    CommandTimeoutException.truncate512(r.output()), e);
        }
    }
}
