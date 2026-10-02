package com.example.vod.common.document;

import com.example.vod.common.domain.media.SplitRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterSplitterTest {

    @Test
    void markdownShouldSplitByH1AndH2() {
        String text = """
                # 前言忽略前缀

                一些前言。

                ## 持久化

                Redis 把数据写盘。

                ## 复制

                主从同步。

                ## 集群

                分片与故障转移。
                """;

        List<ChapterPiece> pieces = ChapterSplitter.split(text, SplitRule.MARKDOWN);

        assertEquals(4, pieces.size());
        assertEquals(1, pieces.get(0).chapterNo());
        assertEquals("前言忽略前缀", pieces.get(0).title());
        assertEquals("持久化", pieces.get(1).title());
        assertEquals("复制", pieces.get(2).title());
        assertEquals("集群", pieces.get(3).title());
        assertTrue(pieces.get(1).content().startsWith("## 持久化"));
        assertTrue(pieces.get(1).wordCount() > 0);
    }

    @Test
    void markdownShouldReturnEmptyWhenNoHeading() {
        List<ChapterPiece> pieces = ChapterSplitter.split("只有正文没有标题", SplitRule.MARKDOWN);
        assertTrue(pieces.isEmpty());
    }

    @Test
    void txtChapterShouldSplitByChapterMarker() {
        String text = """
                第1章 开篇

                你好。

                第二章 进阶

                继续。

                第10章 收尾

                结束。
                """;

        List<ChapterPiece> pieces = ChapterSplitter.split(text, SplitRule.TXT_CHAPTER);

        assertEquals(3, pieces.size());
        assertEquals("第1章 开篇", pieces.get(0).title());
        assertEquals("第二章 进阶", pieces.get(1).title());
        assertEquals("第10章 收尾", pieces.get(2).title());
        assertTrue(pieces.get(0).content().startsWith("第1章 开篇"));
    }

    @Test
    void blankTextShouldReturnEmpty() {
        assertTrue(ChapterSplitter.split(null, SplitRule.MARKDOWN).isEmpty());
        assertTrue(ChapterSplitter.split("   ", SplitRule.MARKDOWN).isEmpty());
    }
}
