package com.example.vod.common.domain.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SplitRuleTest {

    @Test
    void fromParamShouldDefaultToMarkdown() {
        assertEquals(SplitRule.MARKDOWN, SplitRule.fromParam(null));
        assertEquals(SplitRule.MARKDOWN, SplitRule.fromParam(""));
        assertEquals(SplitRule.MARKDOWN, SplitRule.fromParam("  "));
    }

    @Test
    void fromParamShouldParseCaseInsensitive() {
        assertEquals(SplitRule.MARKDOWN, SplitRule.fromParam("markdown"));
        assertEquals(SplitRule.TXT_CHAPTER, SplitRule.fromParam("TXT_CHAPTER"));
    }

    @Test
    void fromParamShouldRejectUnknown() {
        assertThrows(IllegalArgumentException.class, () -> SplitRule.fromParam("BY_PAGE"));
    }
}
