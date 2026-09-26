package com.example.vod.worker.process;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputCollectorTest {

    @Test
    void keepsFullContentWhenUnderLimit() {
        BoundedOutputCollector c = new BoundedOutputCollector(100);
        c.append("hello");
        c.append(" world");

        assertEquals("hello world", c.snapshot());
        assertFalse(c.truncated());
    }

    @Test
    void keepsOnlyTailWithTruncationMark() {
        BoundedOutputCollector c = new BoundedOutputCollector(40);
        c.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        c.append("abcdefghijklmnopqrstuvwxyz");

        String snap = c.snapshot();
        assertTrue(c.truncated());
        assertTrue(snap.length() <= 40);
        assertTrue(snap.startsWith(BoundedOutputCollector.TRUNCATION_MARK));
        assertTrue(snap.endsWith("abcdefghijklmnopqrstuvwxyz")
                || snap.contains("abcdefghijklmnopqrstuvwxyz".substring(10)));
    }

    @Test
    void appendBytesDecodesAsUtf8() {
        BoundedOutputCollector c = new BoundedOutputCollector(64);
        byte[] bytes = "转码日志".getBytes(StandardCharsets.UTF_8);
        c.append(bytes, 0, bytes.length, StandardCharsets.UTF_8);

        assertEquals("转码日志", c.snapshot());
    }
}
