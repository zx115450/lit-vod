package com.example.vod.common.document;

/**
 * 切章结果中的一章：序号、标题、正文（含标题行）。
 *
 * @param chapterNo 从 1 起
 * @param title     去掉 Markdown {@code #} 或保留「第 N 章」后的标题文本
 * @param content   写入 MinIO 的完整章正文
 */
public record ChapterPiece(int chapterNo, String title, String content) {

    /** 章正文 Unicode 码点数（BMP 汉字即字符数）。 */
    public int wordCount() {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.codePointCount(0, content.length());
    }
}
