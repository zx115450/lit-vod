package com.example.vod.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
    public String effectivePublicEndpoint() {
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            return endpoint;
        }
        return publicEndpoint;
    }
}
