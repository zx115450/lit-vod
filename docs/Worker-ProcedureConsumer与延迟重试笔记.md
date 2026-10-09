# Worker：ProcedureConsumer 与延迟重试笔记

> 对应代码：  
> - `vod-worker/.../consumer/ProcedureConsumer.java`  
> - `vod-common/.../messaging/ProcedureRetry.java`  
> - `vod-common/.../messaging/RabbitConfig.java`  
> 相关步骤：[09-Worker 转码闭环](./05-分步实现指南/09-Worker转码闭环.md)。

## 本文解决什么

- `ProcedureConsumer` 收到消息后怎么跑完一轮转码
- 业务侧 3 个队列与重试拓扑分别是什么
- TTL 延迟结束后，消息如何回到工作队列
- `basicAck(..., false)`、`Math.multiplyExact`、ABR 码率字段各自含义

---

## 1. ProcedureConsumer 核心流程

监听队列默认：`vod.procedure`。

```text
收消息
  → 查 MediaTask（没有则 ACK 丢弃）
  → attempt+1，标 RUNNING
  → MinIO 下载 source.mp4
  → ffprobe 时长（超限 → 失败并进 DLQ，不重试）
  → 按 taskType 分支：
       FULL   ：一次出齐 HLS → 媒资 FINISHED → 回调 → ACK
       FAST   ：首档可播 PLAYABLE → 投递 LADDER → 回调 → ACK
       LADDER ：补档 → 媒资 FINISHED → 回调 → ACK
  → finally 清理临时目录
```

失败时（普通异常）：

1. 任务 / 媒资标 FAILED
2. `attempt < maxAttempts` → 发到 `vod.retry` 对应等待队列 → **ACK 当前消息**
3. 次数用尽 → 发到 `vod.dead` / `vod.procedure.dlq` → 失败回调 → ACK

注意：本类**不**调用 `basicNack`；重试靠「ACK + 另发一条到等待队列」。

### ACK 第二个参数

```java
channel.basicAck(deliveryTag, false);
```

- `false`：只确认当前这条（`multiple=false`）
- `true`：确认所有 `deliveryTag ≤ 当前值` 的未确认消息（批量 ACK）

---

## 2. 业务拓扑：3 队列、1 交换机

`RabbitConfig` 声明的是 **3 个业务队列 + 1 个 Direct 交换机**：

| 队列 | 交换机 | routingKey | 用途 |
| --- | --- | --- | --- |
| `vod.procedure` | `vod.direct` | `vod.procedure` | VIDEO 转码（FFmpeg） |
| `vod.document.split` | `vod.direct` | `vod.document.split` | DOCUMENT 切章 |
| `vod.image.thumbnail` | `vod.direct` | `vod.image.thumbnail` | IMAGE 缩略图 |

三个队列共用 `vod.direct`，靠不同 routingKey 分流。

**延迟重试只服务转码**：`vod.procedure`。文档 / 图片两条线不用 `ProcedureRetry`。

---

## 3. ProcedureRetry：延迟重试拓扑

在业务拓扑之外，另声明：

| 组件 | 名称 | 作用 |
| --- | --- | --- |
| 重试交换机 | `vod.retry` | 失败后先发到这里 |
| 等待队列 | `vod.procedure.retry.{N}ms` | 无人消费，只「睡」TTL |
| 死信交换机 | `vod.dead` | 次数用尽后走这里 |
| 死信队列 | `vod.procedure.dlq` | 停放，不再回流业务队列 |

### 消息路径（`maxAttempts=3` 示例）

```text
失败且还能重试
  → vod.retry（routingKey = 等待队列名）
  → vod.procedure.retry.5000ms / 10000ms（睡满 TTL）
  → 死信到 vod.direct + routingKey vod.procedure
  → 回到工作队列 vod.procedure

失败且次数用尽
  → vod.dead → vod.procedure.dlq（停放）
```

### 为何一档一个等待队列

不同 TTL 若挤在同一队列，短延迟消息可能被长延迟堵在队头后面。  
因此 `attempt=1 → 5s`、`attempt=2 → 10s` 各建一队。

延迟步长：`attempt × 5000` 毫秒（`DELAY_STEP_MILLIS`）。

只为还能再试的次数建队列：`attempt = 1 .. maxAttempts-1`。  
例如 `maxAttempts=3` 只建 5s、10s；第 3 次失败进 DLQ。

---

## 4. 延迟结束后，工作队列怎么指定

不是延迟结束再「临时选队列」，而是**建等待队列时写死死信参数**：

```java
QueueBuilder.durable(name)
    .ttl(delayMs)
    .deadLetterExchange(RabbitConfig.EXCHANGE_NAME)   // vod.direct
    .deadLetterRoutingKey(RabbitConfig.ROUTING_KEY) // vod.procedure
    .build();
```

TTL 到期 → Broker 按 `x-dead-letter-exchange` + `x-dead-letter-routing-key` 自动转发 →  
`vod.direct` + `vod.procedure` → 落到工作队列 `vod.procedure`。

---

## 5. 相关小点

### `Math.multiplyExact(safeAttempt, DELAY_STEP_MILLIS)`

普通 `int` 乘法溢出会静默绕回错误值；`multiplyExact` 溢出时抛 `ArithmeticException`。  
正常 `attempt` 很小，几乎不会溢出；属于防御性写法，避免异常数据算出错误 TTL。

### ABR 档位码率（`AbrProperties.Variant`）

| 字段 | 含义 | 单位 | 传给 FFmpeg |
| --- | --- | --- | --- |
| `videoBitrate` | 目标视频码率 | bps | `-b:v` |
| `audioBitrate` | 目标音频码率 | bps | `-b:a` |
| `bandwidth` | master.m3u8 声明带宽 | bps | 通常略大于 video+audio |

例如 `600_000` ≈ 600 kbps 视频；`96_000` ≈ 96 kbps 音频。

---

## 6. 一张总览

```text
API 投递
  → vod.direct ──routingKey──► vod.procedure
                                    │
                                    ▼
                            ProcedureConsumer
                           /       |        \
                       FULL      FAST      LADDER
                         │         │          │
                      成功 ACK   成功 ACK    成功 ACK
                         │         │
                         │      再投递 LADDER 到 vod.direct
                         │
                      失败且可重试
                         → vod.retry → 等待队列(TTL)
                         → 死信回 vod.direct → vod.procedure
                         │
                      失败且用尽
                         → vod.dead → vod.procedure.dlq
```

## 关键源码位置

| 内容 | 路径 |
| --- | --- |
| 消费者主流程 | `video/lite-vod/vod-worker/.../ProcedureConsumer.java` |
| 重试拓扑声明 | `video/lite-vod/vod-common/.../ProcedureRetry.java` |
| 业务队列 / 交换机 | `video/lite-vod/vod-common/.../RabbitConfig.java` |
| ABR 档位码率 | `video/lite-vod/vod-common/.../AbrProperties.java` |
