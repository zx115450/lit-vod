# 06：CommandRunner 增强实现

> 目标：把 `vod-worker` 的外部命令执行层从「能跑 ffmpeg」提升到「超时可靠、输出可控、可测可观测」。  
> 现状代码：`lite-vod/vod-worker/.../process/CommandRunner.java`。  
> 前置阅读：[Java 调用命令行程序使用指南](../Java-ProcessBuilder.md)、[09-Worker 转码闭环](../05-分步实现指南/09-Worker转码闭环.md)。  
> 定位：**工程增强**，可单独小 PR 落地；不改变对外 API / MQ 契约。

## 1. 为什么要增强

当前 `CommandRunner` 作为基础设施已经可用：`List` 传参、合并 stderr、超时意图、中断杀进程、`CommandResult` 结构化返回，符合中小型企业常见写法。

对照更严的进程治理，仍有几处缺口：

| 缺口 | 现状 | 风险 |
| --- | --- | --- |
| 超时与读流顺序 | 先 `readAllBytes` 再 `waitFor(timeout)` | 子进程挂死且不关 stdout 时，读流阻塞，**timeout 套不上** |
| 输出内存 | 全量读入一个 `String` | 长转码日志撑大堆内存 |
| 可执行白名单 | Runner 接受任意 `command.get(0)` | 误用或配置错误时扩大攻击面 |
| 可观测性 | 仅 debug / warn 截断日志 | 难做耗时、失败率、超时率监控 |
| 单测 | 无 `CommandRunner` 专项测试 | 回归靠手工转码 |

增强目标：**不换框架**（不必上 Commons Exec），把现有类收紧到可上生产转码 Worker 的水位。

## 2. 目标与非目标

### 2.1 目标

1. **超时真正生效**：主线程 `waitFor(timeout)`，输出在旁路线程读；超时 `destroyForcibly`
2. **输出有界**：默认只保留尾部 `N` 字符（建议 64 KiB 文本上限，落库仍截 512）
3. **可执行白名单**：默认仅 `ffmpeg` / `ffprobe`（可配置）
4. **结构化日志 / 指标钩子**：commandName、耗时、exitCode、timeout 标记
5. **单测覆盖**：超时、非 0 退出、中断、白名单拒绝

### 2.2 非目标

- 不把转码改成 JavaCV / native 绑定
- 不引入新的进程编排框架或 sidecar
- 不改变 `FfmpegService` 对外方法语义（`transcodeHls` / `probeDuration` 等）
- 不做跨 Worker 的分布式杀进程（单机子进程即可）

## 3. 现状与问题对照

### 3.1 当前主路径（示意）

```text
start()
  → readFully(stdout)     // 阻塞到 EOF（通常≈进程退出）
  → waitFor(timeout)      // 多数情况下进程已结束，timeout 形同虚设
  → exitCode + output
```

注释里「先读流避免管道死锁」方向正确，但实现成「同步读完全部」后，**限时等待失去意义**。

### 3.2 目标主路径

```text
start()
  → 启动 Reader 线程（有界缓冲 / 截断）
  → 主线程 waitFor(timeout)
       ├─ 正常结束 → join Reader → 返回 CommandResult
       └─ 超时     → destroyForcibly → join Reader → 抛 CommandTimeoutException
  → 若主线程被 interrupt → destroyForcibly → 恢复中断标志
```

## 4. 推荐实现设计

### 4.1 接口保持兼容

对外方法签名建议保持：

```java
public CommandResult run(Path workDir, Duration timeout, List<String> command)
        throws IOException, InterruptedException
```

超时仍抛 `CommandTimeoutException`；非 0 退出仍返回 `CommandResult`，由 `FfmpegService` 转成 `TranscodeException`。这样 `FfmpegService` / `ProcedureConsumer` 可少改或不改。

可选增强（第二步再做）：

```java
public CommandResult run(Path workDir, Duration timeout, List<String> command, RunOptions options)
```

`RunOptions`：输出上限、是否允许非白名单、自定义环境变量等。

### 4.2 异步读流 + 有界输出

```text
BoundedOutputCollector
  - maxChars（如 65536）
  - append(chunk)：超过则丢弃头部，只留尾部（环形或「截断标记 + 尾部」）
  - snapshot()：供超时异常与失败日志使用
```

实现要点：

1. `redirectErrorStream(true)` 保持不变，只读一条合并流
2. Reader 用独立线程或 `CompletableFuture`，`daemon=true`，避免卡住 JVM 退出
3. 进程结束后必须 `join` Reader（带短超时），避免泄漏
4. 落库 `error_msg` 仍走现有 `truncate512`，与有界缓冲分层：缓冲为大窗口排障，库字段为短摘要

### 4.3 超时与中断

| 事件 | 行为 |
| --- | --- |
| `waitFor` 返回 false | `destroyForcibly` → 再 `waitFor(5s)` → 抛 `CommandTimeoutException(name, timeout, tailOutput)` |
| `InterruptedException` | `destroyForcibly` → `Thread.currentThread().interrupt()` → 向上抛 |
| Reader 读失败 | 记录 warn，尽量仍回收进程；主路径以 exit / timeout 为准 |

### 4.4 可执行白名单

配置项建议挂在 `worker.*`（或新建 `worker.command.*`）：

```yaml
worker:
  command:
    allowed-binaries:
      - ffmpeg
      - ffprobe
    max-output-chars: 65536
```

校验规则：

1. `command` 非空
2. `command.get(0)` 的 **文件名**（去掉路径）必须在白名单内  
   - 允许 `/usr/bin/ffmpeg`，比较 `ffmpeg`
3. 拒绝 `bash` / `sh` / `cmd.exe` 等壳，避免「白名单形同虚设」

启动时可复用现有「跑一遍 `ffmpeg -version`」探活；白名单与探活一致。

### 4.5 可观测性

每次 `run` 结束打一条 info / warn（成功 info 可抽样，失败与超时必打）：

```text
cmd=ffmpeg exit=0 durationMs=12345 timedOut=false workDir=/tmp/vod/xxx
```

指标（有 Micrometer 再挂；没有就先留空方法 / 日志约定）：

| 指标名（建议） | 标签 | 含义 |
| --- | --- | --- |
| `vod_worker_cmd_duration_seconds` | `cmd`, `result` | 耗时分布 |
| `vod_worker_cmd_total` | `cmd`, `result=ok\|fail\|timeout` | 次数 |

`result`：`ok`（exit 0）、`fail`（exit≠0）、`timeout`、`interrupted`。

注意：日志里只打 **命令名 + 关键参数摘要**，不要把完整 argv 里可能出现的用户路径无差别灌进高基数标签。

## 5. 代码改动面

| 文件 | 改动 |
| --- | --- |
| `CommandRunner.java` | 异步读流、有界输出、白名单、耗时日志 |
| `CommandTimeoutException.java` | 可不变；继续带 `truncatedOutput` |
| `CommandResult.java` | 可选增加 `timedOut` / `duration`；非必须 |
| `WorkerProperties` 或新 `CommandProperties` | 白名单与 `max-output-chars` |
| `application.yml`（worker） | 默认配置 |
| `FfmpegService.java` | 通常零改或仅适配新可选参数 |
| 新增 `CommandRunnerTest` | 用短命令测超时 / 退出码 / 白名单 |

建议 **一个 PR 只做 Runner + 测试 + 配置**，不夹带 ABR / 渐进式改动。

## 6. 实现步骤（建议排期）

### P0：超时可靠（必做）

1. Reader 线程 + `waitFor(timeout)` + 超时强杀
2. 保留 `redirectErrorStream(true)` 与中断处理
3. 用「睡眠超过 timeout 的命令」单测验证必抛 `CommandTimeoutException`

Linux 单测示例命令：

```text
List.of("sleep", "30")   // timeout=2s → 必须超时
List.of("false")         // 或 List.of("sh","-c","exit 7") 在白名单关闭时测 exit≠0
```

白名单开启后，超时测试可临时用配置关闭白名单，或把 `sleep` 仅加入测试 profile。

### P1：有界输出 + 白名单

1. `BoundedOutputCollector`
2. `allowed-binaries` 配置绑定与拒绝路径（抛 `IllegalArgumentException` 或专用异常，Consumer 记 FAILED）
3. 确认 `FfmpegService` 只传 `ffmpeg` / `ffprobe`

### P2：可观测与文档

1. 耗时日志字段固定
2. （可选）Micrometer counter / timer
3. 回写 [Java-ProcessBuilder.md](../Java-ProcessBuilder.md)「超时与读流」小节，指向本文

## 7. 测试计划

| 用例 | 期望 |
| --- | --- |
| 白名单外二进制 | 不启动进程，立即失败 |
| `ffmpeg -version`（集成，需镜像有 ffmpeg） | exit 0，输出非空且长度 ≤ max |
| 超时命令 | `CommandTimeoutException`，进程已销毁 |
| 非 0 退出 | 返回 `CommandResult`，`success()==false`，日志截断 |
| 调用线程 interrupt | 子进程被杀，中断标志恢复 |
| 超长输出 | 内存中仅保留尾部，含截断标记（如 `...[truncated]...`） |

CI：无 ffmpeg 的 job 只跑「白名单 / 超时 / 截断」等不依赖 ffmpeg 的用例；含 ffmpeg 的集成测放在 Worker 镜像或标记 `@EnabledIf`。

## 8. 验收标准

- [ ] 人为构造「只写管道、长时间不退出」的进程时，能在 `timeout` 内返回超时异常（P0 核心）
- [ ] 正常 ffmpeg 转码路径行为与现网一致（成功产物、失败 `error_msg`、MQ 重试策略不变）
- [ ] 默认配置下无法通过 Runner 执行 `bash -c ...`
- [ ] 单测覆盖 P0/P1 主路径；文档与 `Java-ProcessBuilder.md` 交叉引用已更新

## 9. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| Reader 线程泄漏 | `finally` 里 destroy + join；daemon 线程 |
| 有界输出导致排障信息变少 | max 设 64KiB；超时/失败日志打尾部；需要时临时调大配置 |
| 白名单过严（路径写法差异） | 比较 `Path.getFileName()`；文档写明应用 `ffmpeg` 而非绝对路径也可 |
| 超时变严后任务变 FAILED | 与现网 timeout 数值对齐（HLS 仍 30min 级）；先灰度观察超时率 |

回滚：保留旧类名为 `CommandRunnerLegacy` 仅作紧急开关 **不推荐**；更好是特性开关 `worker.command.async-reader-enabled`，默认 true，出问题打 false 回旧路径（旧路径仅过渡一个版本）。

## 10. 与现网组件关系

```text
ProcedureConsumer
    → FfmpegService（拼 argv、解释 exit / 输出）
        → CommandRunner（增强点：进程生命周期 / 输出 / 白名单）
            → OS: ffmpeg / ffprobe
```

业务状态机、ABR、渐进式补档 **不在本文范围**；它们继续依赖 `FfmpegService` 的稳定契约即可。

## 11. 参考

- 现码：`CommandRunner` / `CommandResult` / `CommandTimeoutException`
- [Java-ProcessBuilder.md](../Java-ProcessBuilder.md)（管道死锁、List 传参、destroyForcibly）
- JDK：`Process` / `ProcessBuilder` / `waitFor(long, TimeUnit)`
