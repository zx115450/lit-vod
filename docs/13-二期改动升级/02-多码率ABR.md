# 02：多码率 ABR

> 目标：转出多档 HLS，写 `master.m3u8`，播放器按带宽自适应切档。  
> 依赖：Worker FFmpeg 链路稳定；对象键约定扩展。  
> 上一篇：[分片上传](./01-分片上传Multipart.md)　下一篇：[试看 L2](./03-试看L2.md)

## 1. 为什么要改

首期单档 720p：实现简单，弱网易卡、高清屏浪费清晰度。  
多码率（ABR）用 `master.m3u8` 列出各档子清单，由 hls.js / 原生播放器选档。

## 2. 目标产物结构

建议对象键（示例 `fileId=f7c2a1b0e9d84f6a`）：

```text
hls/f7c2a1b0e9d84f6a/master.m3u8
hls/f7c2a1b0e9d84f6a/720p/index.m3u8
hls/f7c2a1b0e9d84f6a/720p/segment_000.ts
hls/f7c2a1b0e9d84f6a/480p/index.m3u8
hls/f7c2a1b0e9d84f6a/480p/segment_000.ts
hls/f7c2a1b0e9d84f6a/360p/index.m3u8
...
cover/f7c2a1b0e9d84f6a.jpg
```

`media.media_url` 改为指向 **master**：

```text
hls/{fileId}/master.m3u8
```

播放签发 path 同步改为：

```text
/hls/{fileId}/master.m3u8
```

验签仍按「清单 path」绑定；子路径 `720p/segment_xxx.ts` 与现网关一致：同一 `fileId` 目录下共用签名，透传 query。

## 3. FFmpeg 策略（二选一）

### 方案 A：多命令串行（实现简单）

对每个档位跑一遍类似首期的 HLS 命令，改 `scale` 与输出目录，最后手写或脚本生成 `master.m3u8`。

### 方案 B：一条命令多输出（效率更高）

使用 filter_complex 一次解码、多路编码（文档实现时再定参数）。  
注意 Worker 内存与耗时上升。

首期档位建议：

| 档位 | 高度 | 参考视频码率 | 音频 |
| --- | --- | --- | --- |
| 360p | 360 | ~800k | AAC 96k |
| 480p | 480 | ~1400k | AAC 128k |
| 720p | 720 | ~2800k | AAC 128k |

`master.m3u8` 示例：

```text
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
360p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480
480p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
720p/index.m3u8
```

## 4. 代码改造点

| 位置 | 改动 |
| --- | --- |
| `FfmpegService` | 多档转码 + 写 master；或拆 `AbrTranscodeService` |
| `ObjectKeys` | `hlsMaster`、`hlsVariant(fileId, label)`、`hlsSegment(fileId, label, index)` |
| `ProcedureConsumer` | 上传 master + 各档目录；失败任一档则整任务 FAILED（或降级只出 720p，需产品决策） |
| `PlaySignatureService` | `path` 改为 `/hls/{fileId}/master.m3u8` |
| `PlayPathSupport` / 网关 | 确认相对 URI 改写对 `720p/segment_000.ts` 生效 |
| 配置 | `vod.abr.enabled`、`vod.abr.progressive-enabled`、档位列表环境变量 |

## 5. 兼容策略

- 开关关闭：行为与首期完全一致（仅 `index.m3u8` 在 `hls/{fileId}/` 根下）
- 开关开启：新任务出 master；**存量媒资**不强制重转（可选提供「重转码」任务类型）
- 删除媒资：`removePrefix(hls/{fileId}/)` 仍可一次清掉多档

## 6. 资源影响

- CPU / 耗时约按档位数倍增
- 磁盘临时目录与桶存储倍增
- `worker.concurrency` 建议保持 1，避免同机多任务 × 多档打满

## 7. 验收

- [ ] 桶内存在 master 与至少两档子清单 + ts
- [ ] 签发 playUrl 指向 master，hls.js 可播
- [ ] 网关对子路径 ts 验签通过
- [ ] 关闭 ABR 后首期路径回归

## 8. 风险

- master 先上传、子档未齐导致播放失败 → 先传齐变体再传 master（同首期「先 ts 后 m3u8」）
- BANDWIDTH 填写不准影响选档 → 可用 ffprobe 估算或按表固定

## 9. 相关：渐进式多档（远期）

若希望「先出低清可播、再异步补 480p/720p」，见 [渐进式多档转码](./远期能力实现思路/05-渐进式多档转码.md)。与本文「一次出齐」是两种产品策略，建议分 PR。
