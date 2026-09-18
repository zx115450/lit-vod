# 步骤 09：Worker 转码闭环

> 分期：Phase 2。  
> 目标：Worker 消费消息，FFmpeg 出 HLS + 封面，回传 MinIO，媒资变为 `PROCESSED`。  
> 上一篇：[转码任务投递](./08-转码任务投递.md)　下一篇：[播放签名签发](./10-播放签名签发.md)

## 本步要做什么

实现文档第九章的消费逻辑。这是工期最长的一步，建议先用 10 秒短视频打通。

## 消费逻辑

```text
1. 收到 {fileId, objectKey, taskId}
2. 更新 task=RUNNING，attempt+1
3. 下载到 /tmp/vod/{fileId}/source.mp4
4. FFmpeg：HLS + 封面
5. 上传 hls/{fileId}/* 与 cover/{fileId}.jpg
6. ffprobe 取 duration，更新 media=PROCESSED，写入 media_url、cover_url
7. task=SUCCESS，finished_at
8. 删除本地临时目录
```

失败：

- `media.status=FAILED`，`error_msg` 截断到 512
- `task.status=FAILED`
- `attempt < 3` 时抛出异常让 MQ 重试；超过则 ack 并停止（避免毒消息死循环）
- 损坏文件应走测试 T4

超限：时长 > 6 小时或文件过大 → 直接 FAILED，不重试。

并发：每 Worker 容器 `concurrency=1～2`，避免 CPU 打满拖垮同机 API。

## FFmpeg 命令（实现文档原文）

单码率 720p HLS：

```bash
ffmpeg -y -i source.mp4 \
  -vf "scale=-2:720" -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 128k \
  -hls_time 6 -hls_list_size 0 -hls_segment_filename "segment_%03d.ts" \
  -f hls index.m3u8
```

封面（第 3 秒；片长短于 3 秒时改用 0 秒）：

```bash
ffmpeg -y -ss 00:00:03 -i source.mp4 -vframes 1 -q:v 2 cover.jpg
```

时长：

```bash
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 source.mp4
```

Java 侧用 `ProcessBuilder`，工作目录为 `/tmp/vod/{fileId}/`，超时时间要设上限（如 30 分钟）。参数如何拆分、如何避免管道死锁、如何截断 `error_msg`：见 [Java 调用命令行程序使用指南](../Java-ProcessBuilder.md)。

## 操作步骤

### 1. Worker Dockerfile

确认镜像内 `ffmpeg -version`、`ffprobe -version` 可用。

### 2. 实现 `FfmpegService`

封装三个命令的退出码检查；stderr 写入日志，摘要写入 `error_msg`。

### 3. 实现 `ProcedureConsumer`

手动 ack。下载 / 转码 / 上传任一步失败都进失败分支。  
成功后 `media_url` 存 `hls/{fileId}/index.m3u8`，`cover_url` 存 `cover/{fileId}.jpg`。

### 4. 上传 HLS

需上传 `index.m3u8` 与全部 `segment_*.ts`。可用列目录后循环 `putObject` / `uploadObject`。Content-Type：

- `m3u8`：`application/vnd.apple.mpegurl` 或 `application/x-mpegURL`
- `ts`：`video/MP2T`

对象键、封装示例、与 `media_url` / `cover_url` 的对应关系：见 [Worker 写回 MinIO 说明](../Worker-写回MinIO.md)。

### 5. 验证

1. 走完整上传 + commit
2. 等 Worker 日志成功
3. `GET /vod/medias/{fileId}` 的 `status=PROCESSED`，`duration > 0`
4. MinIO 中存在 `hls/.../index.m3u8` 与封面
5. `/tmp/vod/{fileId}` 已删除
6. 用损坏文件验证 FAILED + `error_msg`

## 完成标准

- [ ] 正常 mp4 → PROCESSED，HLS 与封面在桶内
- [ ] 失败可查 `error_msg`，重试不超过 3 次
- [ ] 临时目录不泄漏
- [ ] 多码率、preview 切片 **不做**

## 本步不做

- 不签发播放 URL（下一步）
- 不在 MinIO 上公开读 HLS
