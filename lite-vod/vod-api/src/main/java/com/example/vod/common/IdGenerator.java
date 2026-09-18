package com.example.vod.common;

import java.util.UUID;

/**
 * 生成对外使用的 fileId：UUID 去横线，16 位十六进制字符。
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    public static String fileId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
