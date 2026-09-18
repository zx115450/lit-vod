package com.example.vod.worker.process;

/**
 * 命令执行结果。
 *
 * @param exitCode 退出码，0 表示成功
 * @param output   合并后的 stdout + stderr 文本
 */
public record CommandResult(int exitCode, String output) {
    public boolean success() {
        return exitCode == 0;
    }
}
