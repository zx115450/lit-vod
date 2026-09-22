package com.example.vod.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = UploadPropertiesBindingTest.Config.class)
@TestPropertySource(properties = "vod.upload.multipart-enabled=true")
class UploadPropertiesBindingTest {

    @Autowired
    private UploadProperties uploadProperties;

    @Test
    void multipartEnabledBindsFromProperty() {
        assertTrue(uploadProperties.multipartEnabled());
    }

    @EnableConfigurationProperties(UploadProperties.class)
    static class Config {
    }
}
