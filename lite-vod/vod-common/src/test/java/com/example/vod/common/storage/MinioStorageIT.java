package com.example.vod.common.storage;

import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证业务封装 {@link MinioStorage}：presignedPut / head / statSize / download。
 * MinIO 未启动则 skip。
 */
class MinioStorageIT {

    private static final String FILE_ID = "dev-test-storage";
    private static final String OBJECT_KEY = ObjectKeys.raw(FILE_ID);

    private static MinioStorage storage;
    private static boolean available;

    @BeforeAll
    static void init() {
        String endpoint = env("MINIO_ENDPOINT", "http://localhost:9000");
        String accessKey = env("MINIO_ROOT_USER", "minioadmin");
        String secretKey = env("MINIO_ROOT_PASSWORD", "minioadmin");
        String bucket = env("MINIO_BUCKET", "vod");

        MinioProperties props = new MinioProperties(endpoint, endpoint, accessKey, secretKey, bucket);
        MinioClient client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        MinioAsyncClient asyncClient = MinioAsyncClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        storage = new MinioStorage(client, client, asyncClient, props);
        try {
            storage.ensureBucket();
            available = true;
        } catch (Exception e) {
            available = false;
            System.err.println("MinIO 未连通，跳过封装测试: " + e.getMessage());
        }
    }

    @BeforeEach
    void skipIfDown() {
        Assumptions.assumeTrue(available, "MinIO 未启动，跳过。命令: docker compose up -d minio");
    }

    @AfterEach
    void cleanup() {
        if (available) {
            storage.removePrefix(ObjectKeys.rawPrefix(FILE_ID));
        }
    }

    @Test
    void presignedPutThenHeadAndStatSize() throws Exception {
        byte[] payload = "tiny-mp4-placeholder".getBytes(StandardCharsets.UTF_8);

        assertFalse(storage.head(OBJECT_KEY));

        String uploadUrl = storage.presignedPut(OBJECT_KEY, Duration.ofMinutes(30));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .timeout(Duration.ofSeconds(30))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        assertTrue(storage.head(OBJECT_KEY));
        assertEquals(payload.length, storage.statSize(OBJECT_KEY));

        Path tmp = Files.createTempFile("lite-vod-", ".mp4");
        try {
            storage.download(OBJECT_KEY, tmp);
            assertEquals(new String(payload, StandardCharsets.UTF_8), Files.readString(tmp));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
