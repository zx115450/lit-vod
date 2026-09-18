# Java 调用命令行程序使用指南

> 对应步骤：[09-Worker 转码闭环](./05-分步实现指南/09-Worker转码闭环.md)。  
> 本项目用法：`vod-worker` 用 `ProcessBuilder` 调 `ffmpeg` / `ffprobe`，工作目录 `/tmp/vod/{fileId}/`。  
> JDK 自带 `java.lang.ProcessBuilder`，无需额外依赖。

## 本文解决什么

- Java 为什么用 `ProcessBuilder` 而不是拼一条 shell 字符串
- 如何传参数、设工作目录、设超时、读退出码
- 如何避免 stdout / stderr 管道堵死导致进程挂起
- Worker 里封装 `FfmpegService` 时该检查什么

FFmpeg 各参数的音视频含义见步骤 09，本文只讲「Java 怎么把命令跑起来」。

## 核心概念

| 术语 | 含义 | 本项目例子 |
| --- | --- | --- |
| 可执行文件 | 操作系统里的程序，不经过 JVM | 容器内的 `ffmpeg`、`ffprobe` |
| 参数列表 | 传给程序的独立字符串，不是一整句 shell | `-i`、`source.mp4`、`-f`、`hls` |
| 工作目录 | 进程启动时的当前目录，相对路径相对它解析 | `/tmp/vod/{fileId}/` |
| 标准输入 stdout / stderr | 子进程写出来的文本流 | 转码进度、错误信息 |
| 退出码 | 进程结束时的整数；约定 `0` 成功 | `ffmpeg` 失败时常为 `1` |
| 超时 | Java 侧等待上限，到点必须杀进程 | 文档建议 30 分钟 |

Java 不「内嵌」FFmpeg。Worker 只是起一个操作系统子进程，等它写完 `index.m3u8` 再继续上传。

不要用 `Runtime.getRuntime().exec("ffmpeg -y -i ...")` 把整行命令丢给 shell：空格、引号、`%` 在 Windows 上容易拆错。`ProcessBuilder` 用 **参数数组**，每个 token 一个元素。

## 先用简单命令练手

本机调试多半是 Windows，Worker 容器是 Linux。先跑这些短命令，确认「参数列表、工作目录、stdout、退出码、超时」都通了，再上 FFmpeg。

`echo`、`dir` 在 Windows 上是 cmd 内置命令，**不能**写成 `new ProcessBuilder("echo", "hello")`，必须走 `cmd /c`。Linux 上 `echo`、`pwd` 一般可以直接当可执行文件。

### 1. 看版本

```java
List.of("java", "-version")
List.of("ffmpeg", "-version")
List.of("ffprobe", "-version")
```

`java -version`、`ffmpeg -version` 的信息常写在 **stderr**。练手时记得 `redirectErrorStream(true)`，否则看起来像「没输出」。

### 2. 打印一行、看当前目录

Windows：

```java
List.of("cmd", "/c", "echo", "hello")
List.of("cmd", "/c", "cd")
List.of("cmd", "/c", "echo", "%CD%")
```

Linux / 容器：

```java
List.of("/bin/echo", "hello")
List.of("pwd")
List.of("printenv", "PATH")
List.of("uname", "-a")
```

设 `pb.directory(...)` 后再跑 `pwd` 或 `cd`，用来确认工作目录是否生效。

### 3. 列文件

Windows：

```java
List.of("cmd", "/c", "dir")
```

Linux：

```java
List.of("ls")
List.of("ls", "-l")
List.of("ls", "-l", "source.mp4")
```

### 4. 故意失败，读退出码

Windows：

```java
List.of("cmd", "/c", "exit", "1")
List.of("cmd", "/c", "dir", "C:\\this\\path\\does\\not\\exist")
```

Linux：

```java
List.of("false")
List.of("ls", "/this/path/does/not/exist")
```

约定：退出码 `0` 成功，非 `0` 失败。和后面 `ffmpeg` 失败常为 `1` 一样。

### 5. 超时

不要一上来就等 30 分钟。先用几秒命令练 `waitFor` + `destroyForcibly()`。

Windows：

```java
List.of("timeout", "/t", "5", "/nobreak")
List.of("ping", "-n", "6", "127.0.0.1")
```

Linux：

```java
List.of("sleep", "5")
```

Java 侧 `waitFor(2, TimeUnit.SECONDS)` 应超时并杀进程。

### 6. 再接到本项目的探测命令

有 `ffprobe` 之后，用短 mp4 跑这一条，stdout 应是秒数（例如 `12.345000`）：

```java
List.of("ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        "source.mp4")
```

最小启动示例（把 `command` 换成上面任意一条）：

```java
ProcessBuilder pb = new ProcessBuilder(command);
pb.redirectErrorStream(true);
Process p = pb.start();
String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
int exit = p.waitFor();
System.out.println("exit=" + exit);
System.out.println(output);
```

建议顺序：`java -version` → `echo` / `pwd` → `sleep` 超时 → `ffprobe` 读时长 → 下文三条正式命令。

## 本项目三条命令对应的参数列表

工作目录已是 `/tmp/vod/{fileId}/` 时，输入输出用相对文件名即可。

转 HLS：

```java
List<String> hls = List.of(
        "ffmpeg", "-y", "-i", "source.mp4",
        "-vf", "scale=-2:720",
        "-c:v", "libx264", "-preset", "medium", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
        "-hls_time", "6", "-hls_list_size", "0",
        "-hls_segment_filename", "segment_%03d.ts",
        "-f", "hls", "index.m3u8");
```

注意：`scale=-2:720` 是 **一个** 参数。若拆成两个，FFmpeg 会认错滤镜。`segment_%03d.ts` 交给 FFmpeg 自己解释 `%03d`，不要再套一层 shell。

截封面：

```java
List<String> cover = List.of(
        "ffmpeg", "-y", "-ss", "00:00:03", "-i", "source.mp4",
        "-vframes", "1", "-q:v", "2", "cover.jpg");
```

片长短于 3 秒时把 `"00:00:03"` 换成 `"00:00:00"`。

读时长：

```java
List<String> probe = List.of(
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        "source.mp4");
```

`ffprobe` 成功时把秒数写到 **stdout**（例如 `12.345000`）。`ffmpeg` 转码进度多半在 **stderr**。

## 最小可运行封装

建议做成可复用的 `run` 方法：设目录、合并错误流、限时等待、失败时带上日志摘要。

```java
public final class CommandRunner {

    public static CommandResult run(Path workDir, Duration timeout, List<String> command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = readFully(process.getInputStream());

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new CommandTimeoutException(command.get(0), timeout, truncate(output, 512));
        }

        int exit = process.exitValue();
        return new CommandResult(exit, output);
    }

    private static String readFully(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\r', ' ').replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max);
    }
}
```

`CommandResult` 至少包含 `exitCode` 与 `output`。`exitCode != 0` 时把 `truncate(output, 512)` 写入媒资 `error_msg`。

`FfmpegService` 只组合命令并解释结果：

```java
public void transcodeHls(Path workDir) {
    CommandResult r = CommandRunner.run(workDir, Duration.ofMinutes(30), HLS_CMD);
    if (r.exitCode() != 0) {
        throw new TranscodeException("ffmpeg hls failed: " + truncate(r.output(), 512));
    }
}

public double probeDuration(Path workDir) {
    CommandResult r = CommandRunner.run(workDir, Duration.ofSeconds(30), PROBE_CMD);
    if (r.exitCode() != 0) {
        throw new TranscodeException("ffprobe failed: " + truncate(r.output(), 512));
    }
    return Double.parseDouble(r.output().trim());
}
```

## 必须处理的行为

### 1. 管道死锁

子进程往 stdout / stderr 写，操作系统管道有容量上限。Java 若不读，缓冲区满后 **子进程会卡住**，`waitFor()` 永远不返回。

做法（三选一，本项目用第一种即可）：

1. `pb.redirectErrorStream(true)`，再把合并后的 `getInputStream()` **读完**（可在 `waitFor` 前用线程读，或像上面 `readAllBytes`）
2. `pb.redirectOutput(file)` / `redirectError(file)` 重定向到文件
3. 单独线程分别排空 stdout 与 stderr

不要只调 `waitFor()` 却从不读流。

### 2. 超时

转码可能因损坏文件或超大片卡住。必须用 `waitFor(timeout, unit)`，超时后：

1. `destroyForcibly()`
2. 再短等几秒确认退出
3. 任务标 `FAILED`，摘要写入 `error_msg`

步骤 09 建议转码上限 30 分钟；`ffprobe` 几秒内应结束，可用 30 秒。

被中断时（`InterruptedException`）同样要杀子进程，并恢复中断标志：

```java
} catch (InterruptedException e) {
    process.destroyForcibly();
    Thread.currentThread().interrupt();
    throw e;
}
```

### 3. 退出码

| 退出码 | 含义 | Worker 行为 |
| --- | --- | --- |
| `0` | 成功 | 继续上传 HLS / 写库 |
| 非 `0` | 命令失败 | `FAILED` + `error_msg` |
| 超时被杀 | 无可靠退出码 | 当作失败，不要当成功 |

不要用「输出目录里有没有文件」代替退出码。半截 `index.m3u8` 也会留下文件。

### 4. 工作目录与路径

相对路径相对 `pb.directory(...)`。本项目下载后目录形如：

```text
/tmp/vod/{fileId}/
  source.mp4
  index.m3u8          ← ffmpeg 写出
  segment_000.ts
  cover.jpg
```

跨平台时优先 `Path`：`workDir.resolve("source.mp4")`。不要手拼 `workDir + "/" + name` 再塞进命令；能设工作目录就用相对名，命令更短、日志更好读。

可执行文件名：Linux 容器里是 `ffmpeg`（在 `PATH` 中）。Windows 本机调试可能是 `ffmpeg.exe`，或要写绝对路径。Worker 以 Dockerfile 里的 Linux 为准。

### 5. 环境变量

默认继承 JVM 的环境。需要改 `PATH` 或限制语言时：

```java
Map<String, String> env = pb.environment();
env.put("AV_LOG_FORCE_NOCOLOR", "1");
```

不要把密钥拼进命令行参数；命令行对同机其他用户往往可见。本项目 FFmpeg 不需要密钥。

### 6. 并发

每个 FFmpeg 吃满 CPU。`ProcessBuilder` 本身不限流。步骤 09 要求每 Worker 容器消费并发 `1～2`，避免拖垮同机 API。不要对同一 `fileId` 目录并行跑两条 ffmpeg。

## 常见错误

| 写法 | 问题 |
| --- | --- |
| `new ProcessBuilder("ffmpeg -y -i source.mp4")` | 整句当可执行文件名，会 `IOException: No such file` |
| `cmd.exe /c` 或 `bash -c` 再塞用户文件名 | 注入与转义风险；本项目无必要走 shell |
| 忽略 stderr | 失败时 `error_msg` 为空，无法排障 |
| 不设超时 | 损坏文件拖死 Worker 线程 |
| 超时只 `destroy()` | 部分进程不理 SIGTERM，要用 `destroyForcibly()` |
| 成功后不删 `/tmp/vod/{fileId}` | 磁盘被切片填满 |
| 在 `vod-api` 里同步调 ffmpeg | HTTP 超时，接口被 CPU 拖死 |

校验可执行文件是否存在，可在 Worker 启动时跑一次：

```java
new ProcessBuilder("ffmpeg", "-version").start();
```

Dockerfile 已 `apt-get install ffmpeg`。本地未装则启动即失败，比等到第一条消息再失败更早暴露。

## 与步骤 09 的对应关系

| 消费步骤 | Java 侧 |
| --- | --- |
| 下载到 `/tmp/vod/{fileId}/source.mp4` | MinIO `getObject`，与命令行无关 |
| FFmpeg：HLS + 封面 | `CommandRunner` + 两组参数列表 |
| ffprobe 取 duration | 读 stdout，`Double.parseDouble` |
| 失败 `error_msg` 截断 512 | `truncate(output, 512)` |
| 超时 / 超限 FAILED | `waitFor` 失败或时长 > 6 小时不调转码 |
| 清理临时目录 | `finally` 里删 `workDir`，含成功与失败 |

超大文件、损坏文件应在调 ffmpeg **之前或根据其失败输出** 判定：时长或体积超限直接 `FAILED` 且不重试；损坏文件走测试 T4，`attempt < 3` 可让 MQ 重试。

## 本机快速验证

容器或已安装 FFmpeg 的机器上，先不经过 Java 确认命令本身正确：

```bash
ffmpeg -version
ffprobe -version
cd /tmp/vod/demo
ffmpeg -y -i source.mp4 -vf "scale=-2:720" -c:v libx264 -preset medium -crf 23 \
  -c:a aac -b:a 128k -hls_time 6 -hls_list_size 0 \
  -hls_segment_filename "segment_%03d.ts" -f hls index.m3u8
```

Java 单测可用极短 mp4 调 `CommandRunner`，断言退出码为 0 且存在 `index.m3u8`。CI 若无 ffmpeg，跳过该测试或只在 Worker 镜像里跑。

## 本文不做

- 不解释 CRF、preset、HLS 切片语义（见步骤 09 与前置知识）
- 不引入 JavaCV / 其它 native 绑定；首期只用命令行
- 不在 Windows 上为 Worker 生产环境另写一套命令
