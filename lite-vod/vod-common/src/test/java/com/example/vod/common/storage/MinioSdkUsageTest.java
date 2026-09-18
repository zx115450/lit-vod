package com.example.vod.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinIO Java SDK 用法演示（对接步骤 04）。
 *
 * <p>不启动 Spring，直接 new {@link MinioClient}，方便对照官方 API。
 *
 * <p>前置：本机 MinIO 已监听 9000。可用 Compose：
 * <pre>
 *   cd lite-vod
 *   docker compose up -d minio
 *   cd vod-common
 *   mvn test -Dtest=MinioSdkUsageTest,ObjectKeysTest
 * </pre>
 *
 * <p>MinIO 未启动时本类全部 skip，不影响默认 {@code mvn test}。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MinioSdkUsageTest {

    private static final String ENDPOINT = env("MINIO_ENDPOINT", "http://localhost:9000");
    private static final String ACCESS_KEY = env("MINIO_ROOT_USER", "minioadmin");
    private static final String SECRET_KEY = env("MINIO_ROOT_PASSWORD", "minioadmin");
    private static final String BUCKET = env("MINIO_BUCKET", "vod");

    /** 与文档验证步骤一致：raw/dev-test/source.mp4 */
    private static final String FILE_ID = "dev-test";
    private static final String OBJECT_KEY = ObjectKeys.raw(FILE_ID);

    private static MinioClient minio;
    private static boolean available;
    private static byte[] sampleBytes;

    @BeforeAll
    static void connect() {
        sampleBytes = "lite-vod-minio-sdk-demo".getBytes(StandardCharsets.UTF_8);
        try {
            minio = MinioClient.builder()
                    .endpoint(ENDPOINT)
                    .credentials(ACCESS_KEY, SECRET_KEY)
                    .build();
            minio.listBuckets();
            available = true;
        } catch (Exception e) {
            available = false;
            System.err.println("MinIO 未连通，跳过 SDK 演示: " + e.getMessage());
            System.err.println("请先启动: docker compose up -d minio");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!available) {
            return;
        }
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(OBJECT_KEY)
                    .build());
        } catch (ErrorResponseException ignored) {
            // 对象可能已被最后一个用例删掉
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. 创建客户端并确保桶 vod 存在")
    void ensureBucket() throws Exception {
        skipIfMinioDown();

        boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build());
        if (!exists) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        assertTrue(minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
    }

    @Test
    @Order(2)
    @DisplayName("2. putObject：服务端用密钥直接写入对象")
    void putObject() throws Exception {
        skipIfMinioDown();

        try (InputStream in = new ByteArrayInputStream(sampleBytes)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(OBJECT_KEY)
                    .stream(in, sampleBytes.length, -1)
                    .contentType("video/mp4")
                    .build());
        }

        StatObjectResponse stat = minio.statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(OBJECT_KEY)
                .build());
        assertEquals(sampleBytes.length, stat.size());
    }

    @Test
    @Order(3)
    @DisplayName("3. getPresignedObjectUrl(PUT)：模拟浏览器直传 uploadUrl")
    void presignedPutLikeBrowser() throws Exception {
        skipIfMinioDown();

        minio.removeObject(RemoveObjectArgs.builder()
                .bucket(BUCKET)
                .object(OBJECT_KEY)
                .build());
        assertFalse(objectExists(OBJECT_KEY));

        // 签名只覆盖「对这个 object 做 PUT」，不要额外带 Content-Type，
        // 否则浏览器/HttpClient 多一个头就会 403（签名字符串对不上）。
        String uploadUrl = minio.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(BUCKET)
                        .object(OBJECT_KEY)
                        .expiry(1, TimeUnit.HOURS)
                        .build());

        assertTrue(uploadUrl.startsWith(ENDPOINT));
        assertTrue(uploadUrl.contains("X-Amz-Algorithm"));
        assertTrue(uploadUrl.contains(OBJECT_KEY));
        System.out.println("uploadUrl = " + uploadUrl);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(uploadUrl))
                        .timeout(Duration.ofSeconds(30))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(sampleBytes))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "预签名 PUT 应成功: " + response.body());
        assertTrue(objectExists(OBJECT_KEY));
    }

    @Test
    @Order(4)
    @DisplayName("4. statObject / getObject：Head 存在性、读 size、下载字节")
    void statAndGetObject() throws Exception {
        skipIfMinioDown();

        StatObjectResponse stat = minio.statObject(StatObjectArgs.builder()
                .bucket(BUCKET)
                .object(OBJECT_KEY)
                .build());
        assertEquals(sampleBytes.length, stat.size());

        byte[] downloaded;
        try (InputStream in = minio.getObject(GetObjectArgs.builder()
                .bucket(BUCKET)
                .object(OBJECT_KEY)
                .build())) {
            downloaded = in.readAllBytes();
        }
        assertArrayEquals(sampleBytes, downloaded);
    }

    @Test
    @Order(5)
    @DisplayName("5. removeObject：对象删除后 Head 失败")
    void removeObject() throws Exception {
        skipIfMinioDown();

        minio.removeObject(RemoveObjectArgs.builder()
                .bucket(BUCKET)
                .object(OBJECT_KEY)
                .build());
        assertFalse(objectExists(OBJECT_KEY));
    }

    private static void skipIfMinioDown() {
        Assumptions.assumeTrue(available, "MinIO 未启动，跳过。命令: docker compose up -d minio");
    }

    private static boolean objectExists(String objectKey) throws Exception {
        try {
            minio.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw e;
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
