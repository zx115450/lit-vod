# FFmpeg 常用指南

> 目标：把日常转码、截图、探针、HLS 切片中最常用的 FFmpeg / ffprobe 命令与参数整理成一份速查手册，便于在 Lite VOD 二期 ABR、Worker 转码、封面截取等场景中快速复用。
>
> 配套文档：
> - [Worker 转码闭环](05-分步实现指南/09-Worker转码闭环.md)
> - [多码率 ABR](13-二期改动升级/02-多码率ABR.md)
> - [Java 调用命令行程序使用指南](Java-ProcessBuilder.md)

---

## 1. 命令行基本结构

```bash
ffmpeg [全局选项] -i 输入文件 [输入选项] [滤镜] [输出选项] 输出文件
```

常用全局选项：

- `-y`：覆盖输出文件，不询问
- `-hide_banner`：隐藏版本和编译信息，日志更干净
- `-loglevel error` / `warning` / `info`：控制日志级别

输入与输出选项可以出现多次，形成多输入 / 多输出。

---

## 2. 常用查询命令

### 2.1 查看文件信息

```bash
ffprobe -v error -show_format -show_streams input.mp4
```

精简版（只看视频流宽高）：

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 input.mp4
```

只看时长：

```bash
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 input.mp4
```

只看视频码率：

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=bit_rate -of default=noprint_wrappers=1:nokey=1 input.mp4
```

---

## 3. 转码与重新封装

### 3.1 最简单的压制

```bash
ffmpeg -i input.mp4 -c:v libx264 -crf 23 -preset medium -c:a aac -b:a 128k output.mp4
```

参数说明：

- `-c:v libx264`：视频编码器用 H.264
- `-crf 23`：恒定质量模式，数值越小画质越好、体积越大，常用 18～28
- `-preset medium`：编码速度与压缩率权衡，从快到慢：`ultrafast` / `superfast` / `veryfast` / `faster` / `fast` / `medium` / `slow` / `slower` / `veryslow`
- `-c:a aac`：音频编码器用 AAC
- `-b:a 128k`：音频目标码率 128 kbps

### 3.2 只换容器，不重新编码

```bash
ffmpeg -i input.mp4 -c copy output.mkv
```

`-c copy` 表示音视频都直接复制，速度极快，适合改封装格式。

### 3.3 提取音频

```bash
ffmpeg -i input.mp4 -vn -c:a copy output.aac
```

`-vn` 表示不要视频流。

---

## 4. 画面缩放与裁剪

### 4.1 等比缩放

保持宽高比，宽度自动计算且为偶数：

```bash
ffmpeg -i input.mp4 -vf "scale=-2:720" -c:v libx264 -c:a copy output.mp4
```

高度自适应：

```bash
ffmpeg -i input.mp4 -vf "scale=1280:-2" -c:v libx264 -c:a copy output.mp4
```

### 4.2 强制分辨率并加黑边

```bash
ffmpeg -i input.mp4 -vf "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2" -c:v libx264 output.mp4
```

### 4.3 裁剪

```bash
ffmpeg -i input.mp4 -vf "crop=1280:720:0:0" -c:v libx264 output.mp4
```

参数：`crop=宽:高:x:y`。

---

## 5. 封面与截图

### 5.1 截取第 3 秒画面

```bash
ffmpeg -ss 00:00:03 -i input.mp4 -vframes 1 -q:v 2 cover.jpg
```

### 5.2 按时间间隔取多张图

```bash
ffmpeg -i input.mp4 -vf "fps=1/10,scale=320:-2" -q:v 2 thumbnails_%03d.jpg
```

每 10 秒一张缩略图。

---

## 6. HLS 切片

### 6.1 单码率 HLS

```bash
ffmpeg -y -i input.mp4 \
  -vf "scale=-2:720" \
  -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 128k \
  -hls_time 6 -hls_list_size 0 \
  -hls_segment_filename "segment_%03d.ts" \
  -f hls index.m3u8
```

关键参数：

- `-hls_time 6`：每个切片目标时长 6 秒
- `-hls_list_size 0`：保留所有切片；点播通常用 0
- `-hls_segment_filename "segment_%03d.ts"`：切片文件名格式
- `-f hls index.m3u8`：输出清单文件

### 6.2 关键帧对齐

多码率 ABR 要求各档切片边界对齐，否则切换时会花屏。常用做法：

```bash
ffmpeg -i input.mp4 \
  -g 250 -keyint_min 250 -sc_threshold 0 \
  -force_key_frames "expr:gte(t,n_forced*6)" \
  -hls_time 6 -f hls index.m3u8
```

说明：

- `-g 250`：最大 GOP 长度（按帧数）
- `-sc_threshold 0`：禁止按场景切换自动插入关键帧
- `-force_key_frames`：强制每隔 6 秒一个关键帧

---

## 7. filter_complex 多路输出

一次解码，同时输出多个档位：

```bash
ffmpeg -y -i input.mp4 \
-filter_complex "
  [0:v]split=3[v0][v1][v2];
  [v0]scale=-2:360[v0o];
  [v1]scale=-2:480[v1o];
  [v2]scale=-2:720[v2o]
" \
-map [v0o] -map 0:a -c:v libx264 -b:v 600k  -c:a aac -b:a 96k  -f hls -hls_time 6 -hls_segment_filename "360p/segment_%03d.ts" 360p/index.m3u8 \
-map [v1o] -map 0:a -c:v libx264 -b:v 1000k -c:a aac -b:a 128k -f hls -hls_time 6 -hls_segment_filename "480p/segment_%03d.ts" 480p/index.m3u8 \
-map [v2o] -map 0:a -c:v libx264 -b:v 2200k -c:a aac -b:a 128k -f hls -hls_time 6 -hls_segment_filename "720p/segment_%03d.ts" 720p/index.m3u8
```

要点：

- `[0:v]split=3` 把输入视频分成 3 路
- 每路做 `scale` 后输出带标签的 `[v0o]`、`[v1o]`、`[v2o]`
- 每个输出用 `-map` 指定视频和音频
- 音频会被重新编码 3 次；如果对音频档位没有区分需求，通常用同一码率

---

## 8. master.m3u8 示例

多码率产物需要一份顶层清单：

```text
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480
480p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
720p/index.m3u8
```

说明：

- `BANDWIDTH` 以 bps 为单位，通常取 video + audio 再留一点开销
- `RESOLUTION` 可选，但建议给出，便于播放器显示清晰度档位
- 播放器先拉 `master.m3u8`，再根据带宽选择子清单

---

## 9. 常用参数速查表

| 参数 | 作用 |
| --- | --- |
| `-i` | 指定输入文件 |
| `-y` | 强制覆盖输出 |
| `-c:v` | 视频编码器，如 `libx264`、`copy` |
| `-c:a` | 音频编码器，如 `aac`、`copy` |
| `-crf` | x264 质量模式参数，常用 18～28 |
| `-b:v` | 视频目标码率，如 `2000k` |
| `-maxrate` / `-bufsize` | CBR / 限码率模式 |
| `-preset` | 编码速度预设 |
| `-vf` | 简单视频滤镜，如 `scale`、`crop` |
| `-filter_complex` | 复杂滤镜图，支持多路输入输出 |
| `-map` | 选择输入流映射到输出 |
| `-ss` | 起始时间 |
| `-t` | 持续时长 |
| `-vn` / `-an` | 禁用视频 / 音频 |

---

## 10. 调试与排错

### 10.1 先看命令会不会跑

```bash
ffmpeg -version
ffprobe -version
```

### 10.2 查看滤镜图是否按预期连接

```bash
ffmpeg -i input.mp4 -filter_complex "[0:v]scale=-2:360[vo]" -map [vo] -f null -
```

`-f null -` 不输出文件，只验证滤镜链。

### 10.3 常见错误

| 错误 | 原因 / 解决 |
| --- | --- |
| `Unknown encoder 'libx264'` | 编译时未启用 libx264，换用官方 build 或重新编译 |
| `Filtergraph 'scale=-2:360' was specified through...` | `-vf` 与 `-filter_complex` 混用，统一用一种 |
| `Output file #0 does not contain any stream` | 忘记 `-map` 或滤镜输出标签未连接 |
| `Invalid data found when processing input` | 输入文件损坏或不是 FFmpeg 支持的格式 |
| 切片时长不齐 | 未强制关键帧，原片为可变帧率，加 `-force_key_frames` 和 `-g` |

---

## 11. 在 Lite VOD 中的落地

- Worker 启动时先执行 `ffmpeg -version` / `ffprobe -version`，提前暴露环境缺失问题
- 转码统一在工作目录 `/tmp/vod/{fileId}/` 下用相对文件名执行，产物直接在该目录生成
- 单档命令见 [09-Worker转码闭环](05-分步实现指南/09-Worker转码闭环.md)
- 多档命令见 [02-多码率ABR](13-二期改动升级/02-多码率ABR.md)
- Java 侧用 `ProcessBuilder` 调用，注意超时、stderr 读取、退出码判断，详见 [Java-ProcessBuilder.md](Java-ProcessBuilder.md)
