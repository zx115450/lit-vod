package com.example.vod.worker.ffmpeg;

import com.example.vod.common.config.AbrProperties;
import com.example.vod.common.storage.ObjectKeys;
import com.example.vod.worker.process.CommandResult;
import com.example.vod.worker.process.CommandRunner;
import com.example.vod.worker.process.CommandTimeoutException;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 封装步骤 09 的三条命令：HLS 转码、截封面、读时长。
 *
 * <p>所有命令的工作目录统一为 {@code /tmp/vod/{fileId}/}，命令里用相对文件名。
 * 命令本身与参数含义见 docs/05-分步实现指南/09-Worker转码闭环.md。
 *
 * <p>二期 ABR：单 FFmpeg 进程一次解码、多路编码，产出多档 HLS + master.m3u8。
 * 二期渐进式：先出首档（默认 360p）写单档 master 标可播，再异步补齐其余档并覆盖 master。
 */
@Slf4j
@Service
public class FfmpegService {

    private static final int HLS_TIME_SECONDS = 6;

    /** 单码率 720p HLS。注意 scale=-2:720 是一个参数。 */
    private static final List<String> HLS_CMD = List.of(
            "ffmpeg", "-y", "-i", "source.mp4",
            "-vf", "scale=-2:720",
            "-c:v", "libx264", "-preset", "medium", "-crf", "23",
            "-c:a", "aac", "-b:a", "128k",
            "-hls_time", String.valueOf(HLS_TIME_SECONDS), "-hls_list_size", "0",
            "-hls_segment_filename", "segment_%03d.ts",
            "-f", "hls", "index.m3u8");

    /** 截封面，第 3 秒。片长短于 3 秒时改用 0 秒（由调用方判断后传 startTime）。 */
    private static List<String> coverCmd(String startTime) {
        return List.of(
                 "ffmpeg", "-y", "-ss", startTime, "-i", "source.mp4",
                "-vframes", "1", "-q:v", "2", "cover.jpg");
    }

    /** 读时长（秒）。失败抛 {@link TranscodeException}。 */
    private static final List<String> PROBE_DURATION_CMD = List.of(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            "source.mp4");

    /** 读原片分辨率，用于 master.m3u8 的 RESOLUTION。 */
    private static final List<String> PROBE_RESOLUTION_CMD = List.of(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-of", "csv=s=x:p=0",
            "source.mp4");

    private static final Duration HLS_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration HLS_TIMEOUT_PER_VARIANT = Duration.ofMinutes(30);
    private static final Duration COVER_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final CommandRunner runner;
    private final AbrProperties abrProperties;
    private final com.example.vod.common.storage.MinioStorage minioStorage;

    public FfmpegService(CommandRunner runner, AbrProperties abrProperties,
                         com.example.vod.common.storage.MinioStorage minioStorage) {
        this.runner = runner;
        this.abrProperties = abrProperties;
        this.minioStorage = minioStorage;
    }

    /**
     * 是否开启 ABR 多码率。
     */
    public boolean isAbrEnabled() {
        return abrProperties.enabled();
    }

    /**
     * 是否开启渐进式多档。
     */
    public boolean isProgressiveEnabled() {
        return abrProperties.progressiveEnabled();
    }

    /**
     * 转码为 HLS。ABR 关闭时写根目录 {@code index.m3u8}；开启时写多档 + {@code master.m3u8}。
     */
    public void transcodeHls(Path workDir) {
        if (!abrProperties.enabled()) {
            transcodeHlsSingle(workDir);
            return;
        }
        transcodeHlsAbr(workDir);
    }

    /**
     * 快路径：只出首档（默认 360p）。
     */
    public void transcodeFast(Path workDir) {
        AbrProperties.Variant fast = abrProperties.fastVariantConfig();
        transcodeVariant(workDir, fast, abrProperties.fastPreset());
    }

    /**
     * 补档路径：依次出剩余档位。
     */
    public void transcodeLadder(Path workDir) {
        List<AbrProperties.Variant> ladder = abrProperties.ladderVariants();
        if (ladder.isEmpty()) {
            log.warn("no ladder variants, skip ladder transcode");
            return;
        }
        for (AbrProperties.Variant v : ladder) {
            transcodeVariant(workDir, v, abrProperties.ladderPreset());
        }
    }

    private void transcodeVariant(Path workDir, AbrProperties.Variant variant, String preset) {
        try {
            Files.createDirectories(workDir.resolve(variant.label()));
        } catch (IOException e) {
            throw new TranscodeException("create variant dir failed: " + variant.label(), "", e);
        }

        List<String> cmd = buildVariantCommand(variant, preset);
        Duration timeout = HLS_TIMEOUT_PER_VARIANT;

        CommandResult r;
        try {
            r = runner.run(workDir, timeout, cmd);
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffmpeg variant timeout: " + variant.label(), e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffmpeg variant io error: " + variant.label(), "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffmpeg variant failed: " + variant.label() + " exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }
        log.info("variant done: {} {}", workDir, variant.label());
    }

    private List<String> buildVariantCommand(AbrProperties.Variant v, String preset) {
        return List.of(
                "ffmpeg", "-y", "-i", "source.mp4",
                "-vf", "scale=-2:" + v.height(),
                "-c:v", "libx264",
                "-preset", preset,
                "-b:v", String.valueOf(v.videoBitrate()),
                "-maxrate", String.valueOf(v.videoBitrate()),
                "-bufsize", String.valueOf(v.videoBitrate() * 2),
                "-c:a", "aac",
                "-b:a", String.valueOf(v.audioBitrate()),
                "-g", "250",
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + HLS_TIME_SECONDS + ")",
                "-f", "hls",
                "-hls_time", String.valueOf(HLS_TIME_SECONDS),
                "-hls_list_size", "0",
                "-hls_segment_filename", v.label() + "/segment_%03d.ts",
                v.label() + "/index.m3u8"
        );
    }

    private void transcodeHlsSingle(Path workDir) {
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

    private void transcodeHlsAbr(Path workDir) {
        List<AbrProperties.Variant> variants = abrProperties.variants();
        if (variants.isEmpty()) {
            throw new IllegalStateException("abr enabled but no variants configured");
        }

        int[] resolution = probeResolution(workDir);
        int srcWidth = resolution[0];
        int srcHeight = resolution[1];

        for (AbrProperties.Variant v : variants) {
            try {
                Files.createDirectories(workDir.resolve(v.label()));
            } catch (IOException e) {
                throw new TranscodeException("create variant dir failed: " + v.label(), "", e);
            }
        }

        List<String> cmd = buildAbrCommand(variants);
        Duration timeout = HLS_TIMEOUT_PER_VARIANT.multipliedBy(Math.max(1, variants.size()));

        CommandResult r;
        try {
            r = runner.run(workDir, timeout, cmd);
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffmpeg abr timeout", e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffmpeg abr io error", "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffmpeg abr failed exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }

        writeMasterForVariants(workDir, variants);
        log.info("abr hls done: {} variants={}", workDir, variants.size());
    }

    private List<String> buildAbrCommand(List<AbrProperties.Variant> variants) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add("source.mp4");

        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]split=").append(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            filter.append("[v").append(i).append("]");
        }
        for (int i = 0; i < variants.size(); i++) {
            filter.append(";[v").append(i).append("]scale=-2:").append(variants.get(i).height()).append("[v").append(i).append("o]");
        }
        cmd.add("-filter_complex");
        cmd.add(filter.toString());

        for (int i = 0; i < variants.size(); i++) {
            AbrProperties.Variant v = variants.get(i);
            cmd.add("-map");
            cmd.add("[v" + i + "o]");
            cmd.add("-map");
            cmd.add("0:a");

            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("medium");
            cmd.add("-b:v");
            cmd.add(String.valueOf(v.videoBitrate()));
            cmd.add("-maxrate");
            cmd.add(String.valueOf(v.videoBitrate()));
            cmd.add("-bufsize");
            cmd.add(String.valueOf(v.videoBitrate() * 2));

            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add(String.valueOf(v.audioBitrate()));

            cmd.add("-g");
            cmd.add("250");
            cmd.add("-sc_threshold");
            cmd.add("0");
            cmd.add("-force_key_frames");
            cmd.add("expr:gte(t,n_forced*" + HLS_TIME_SECONDS + ")");

            cmd.add("-f");
            cmd.add("hls");
            cmd.add("-hls_time");
            cmd.add(String.valueOf(HLS_TIME_SECONDS));
            cmd.add("-hls_list_size");
            cmd.add("0");
            cmd.add("-hls_segment_filename");
            cmd.add(v.label() + "/segment_%03d.ts");
            cmd.add(v.label() + "/index.m3u8");
        }

        return cmd;
    }

    /**
     * 按给定档位生成 master.m3u8（用于一次出齐或快路径首版 master）。
     */
    public void writeMasterForVariants(Path workDir, List<AbrProperties.Variant> variants) {
        int[] resolution = probeResolution(workDir);
        StringBuilder master = new StringBuilder();
        master.append("#EXTM3U\n");
        for (AbrProperties.Variant v : variants) {
            int displayWidth = scaledWidth(resolution[0], resolution[1], v.height());
            master.append("#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append(v.bandwidth())
                    .append(",RESOLUTION=")
                    .append(displayWidth)
                    .append("x")
                    .append(v.height())
                    .append("\n")
                    .append(v.label())
                    .append("/index.m3u8\n");
        }

        Path masterPath = workDir.resolve("master.m3u8");
        try {
            Files.writeString(masterPath, master.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TranscodeException("write master.m3u8 failed", "", e);
        }
    }

    /**
     * 渐进式补档后重写 master：按桶内已存在档位 + 本工作目录新档位生成并集。
     */
    public void writeMasterFromExisting(Path workDir, String fileId) {
        Set<String> existingLabels = new HashSet<>();

        // 1) 桶内已有子清单
        Iterable<Result<Item>> results = minioStorage.listObjectsByPrefix(ObjectKeys.hlsPrefix(fileId));
        for (Result<Item> result : results) {
            try {
                String name = result.get().objectName();
                String relative = name.substring(ObjectKeys.hlsPrefix(fileId).length());
                int slash = relative.indexOf('/');
                if (slash > 0 && relative.endsWith("/index.m3u8")) {
                    existingLabels.add(relative.substring(0, slash));
                }
            } catch (Exception e) {
                log.warn("list object failed when rebuilding master: fileId={}", fileId, e);
            }
        }

        // 2) 本工作目录新产物
        try (var stream = Files.newDirectoryStream(workDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("index.m3u8"))) {
                    existingLabels.add(p.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("scan workDir failed: {} {}", workDir, e.toString());
        }

        if (existingLabels.isEmpty()) {
            throw new IllegalStateException("no variants found to rebuild master for fileId=" + fileId);
        }

        // 3) 按配置顺序排列
        List<AbrProperties.Variant> ordered = new ArrayList<>();
        Map<String, AbrProperties.Variant> configured = new LinkedHashMap<>();
        for (AbrProperties.Variant v : abrProperties.variants()) {
            configured.put(v.label(), v);
        }
        for (AbrProperties.Variant v : configured.values()) {
            if (existingLabels.contains(v.label())) {
                ordered.add(v);
            }
        }
        if (ordered.isEmpty()) {
            // 兜底：配置外档位按名字字典序
            ordered = existingLabels.stream()
                    .sorted(Comparator.comparingInt(this::labelOrder).thenComparing(Comparator.naturalOrder()))
                    .map(label -> configured.getOrDefault(label,
                            new AbrProperties.Variant(label, 0, 0, 0, 0)))
                    .toList();
        }

        writeMasterForVariants(workDir, ordered);
    }

    private int labelOrder(String label) {
        return switch (label) {
            case "360p" -> 0;
            case "480p" -> 1;
            case "720p" -> 2;
            case "1080p" -> 3;
            default -> 99;
        };
    }

    private int scaledWidth(int srcWidth, int srcHeight, int targetHeight) {
        if (srcHeight <= 0) {
            return targetHeight * 16 / 9;
        }
        int width = (int) Math.round(srcWidth * (double) targetHeight / srcHeight);
        return (width % 2 == 0) ? width : width + 1;
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
            r = runner.run(workDir, PROBE_TIMEOUT, PROBE_DURATION_CMD);
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

    private int[] probeResolution(Path workDir) {
        CommandResult r;
        try {
            r = runner.run(workDir, PROBE_TIMEOUT, PROBE_RESOLUTION_CMD);
        } catch (CommandTimeoutException e) {
            throw new TranscodeException("ffprobe resolution timeout", e.truncatedOutput(), e);
        } catch (IOException | InterruptedException e) {
            throw new TranscodeException("ffprobe resolution io error", "", e);
        }
        if (!r.success()) {
            throw new TranscodeException("ffprobe resolution failed exit=" + r.exitCode(),
                    CommandTimeoutException.truncate512(r.output()));
        }
        String out = r.output().trim();
        int x = out.indexOf('x');
        if (x <= 0 || x >= out.length() - 1) {
            throw new TranscodeException("ffprobe resolution parse failed: " + out,
                    CommandTimeoutException.truncate512(out));
        }
        try {
            int width = Integer.parseInt(out.substring(0, x));
            int height = Integer.parseInt(out.substring(x + 1));
            return new int[]{width, height};
        } catch (NumberFormatException e) {
            throw new TranscodeException("ffprobe resolution parse failed: " + out,
                    CommandTimeoutException.truncate512(out), e);
        }
    }
}
