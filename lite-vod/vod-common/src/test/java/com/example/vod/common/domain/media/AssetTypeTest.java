package com.example.vod.common.domain.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTypeTest {

    @Test
    void fromParamShouldDefaultToVideo() {
        assertEquals(AssetType.VIDEO, AssetType.fromParam(null));
        assertEquals(AssetType.VIDEO, AssetType.fromParam(""));
        assertEquals(AssetType.VIDEO, AssetType.fromParam("  "));
    }

    @Test
    void fromParamShouldParseCaseInsensitive() {
        assertEquals(AssetType.DOCUMENT, AssetType.fromParam("document"));
        assertEquals(AssetType.IMAGE, AssetType.fromParam("IMAGE"));
    }

    @Test
    void fromParamShouldRejectUnknown() {
        assertThrows(IllegalArgumentException.class, () -> AssetType.fromParam("UNKNOWN"));
    }

    @Test
    void chapterShouldNotBeUploadable() {
        assertFalse(AssetType.CHAPTER.uploadable());
        assertTrue(AssetType.VIDEO.uploadable());
        assertTrue(AssetType.DOCUMENT.uploadable());
    }
}
