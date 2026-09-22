package com.example.vod.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = AbrPropertiesBindingTest.Config.class)
@TestPropertySource(properties = {
        "vod.abr.enabled=true",
        "vod.abr.variants[0].label=360p",
        "vod.abr.variants[0].height=360",
        "vod.abr.variants[0].video-bitrate=600000",
        "vod.abr.variants[0].audio-bitrate=96000",
        "vod.abr.variants[0].bandwidth=800000"
})
class AbrPropertiesBindingTest {

    @Autowired
    private AbrProperties abrProperties;

    @Test
    void defaultShouldBeDisabledWithThreeVariants() {
        AbrProperties defaults = new AbrProperties(false, null);
        assertFalse(defaults.enabled());
        assertEquals(3, defaults.variants().size());
        assertEquals("720p", defaults.variants().get(2).label());
    }

    @Test
    void bindsEnabledAndCustomVariant() {
        assertTrue(abrProperties.enabled());
        assertEquals(1, abrProperties.variants().size());
        AbrProperties.Variant v = abrProperties.variants().get(0);
        assertEquals("360p", v.label());
        assertEquals(360, v.height());
        assertEquals(600_000, v.videoBitrate());
        assertEquals(96_000, v.audioBitrate());
        assertEquals(800_000, v.bandwidth());
    }

    @EnableConfigurationProperties(AbrProperties.class)
    static class Config {
    }
}
