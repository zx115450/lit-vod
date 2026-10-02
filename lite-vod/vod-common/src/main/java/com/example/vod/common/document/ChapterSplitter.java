package com.example.vod.common.document;

import com.example.vod.common.domain.media.SplitRule;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCUMENT 切章：按 {@link SplitRule} 将全文拆成有序章节列表。
 *
 * <p>切不出至少 1 章时返回空列表，由调用方将父媒资标 FAILED。
 */
public final class ChapterSplitter {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,2})\\s+(.+?)\\s*$");
    private static final Pattern TXT_CHAPTER_HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千零0-9]+章\\s*.*)$");

    private ChapterSplitter() {
    }

    public static List<ChapterPiece> split(String text, SplitRule rule) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        SplitRule effective = rule == null ? SplitRule.MARKDOWN : rule;
        return switch (effective) {
            case MARKDOWN -> splitByLinePattern(text, MARKDOWN_HEADING, true);
            case TXT_CHAPTER -> splitByLinePattern(text, TXT_CHAPTER_HEADING, false);
        };
    }

    /**
     * @param stripMarkdownHashes 为 true 时从标题去掉行首 {@code #}（MARKDOWN）；TXT 保留整行作标题
     */
    private static List<ChapterPiece> splitByLinePattern(String text, Pattern headingPattern,
                                                         boolean stripMarkdownHashes) {
        String[] lines = text.split("\\R", -1);
        List<Integer> headingIndexes = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            Matcher m = headingPattern.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            headingIndexes.add(i);
            if (stripMarkdownHashes) {
                titles.add(m.group(2).trim());
            } else {
                titles.add(m.group(1).trim());
            }
        }

        if (headingIndexes.isEmpty()) {
            return List.of();
        }

        List<ChapterPiece> pieces = new ArrayList<>(headingIndexes.size());
        for (int c = 0; c < headingIndexes.size(); c++) {
            int start = headingIndexes.get(c);
            int end = (c + 1 < headingIndexes.size()) ? headingIndexes.get(c + 1) : lines.length;
            String content = joinLines(lines, start, end);
            String title = titles.get(c);
            if (title.isBlank()) {
                title = "第" + (c + 1) + "章";
            }
            pieces.add(new ChapterPiece(c + 1, title, content));
        }
        return pieces;
    }

    private static String joinLines(String[] lines, int startInclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = startInclusive; i < endExclusive; i++) {
            if (i > startInclusive) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
