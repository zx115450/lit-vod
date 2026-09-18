package com.example.vod.worker.callback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackNotifierTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void skipsWhenUrlBlank() {
        CallbackNotifier notifier = new CallbackNotifier(CallbackProperties.of("", 500, 500, 2));
        notifier.notifyAsync(CallbackPayload.processed("f1", "cover/f1.jpg", 1.0));
        notifier.shutdown();
    }

    @Test
    void postsJsonAndRetriesThenSucceeds() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cb", exchange -> {
            int n = hits.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            assertTrue(new String(body, StandardCharsets.UTF_8).contains("\"fileId\":\"abc\""));
            int code = n < 2 ? HttpStatus.INTERNAL_SERVER_ERROR.value() : HttpStatus.OK.value();
            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(code, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            if (code == 200) {
                done.countDown();
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cb";
        CallbackNotifier notifier = new CallbackNotifier(CallbackProperties.of(url, 1000, 1000, 3));
        notifier.notifyAsync(CallbackPayload.processed("abc", "cover/abc.jpg", 12.5));

        assertTrue(done.await(5, TimeUnit.SECONDS), "callback should succeed after retry");
        assertEquals(2, hits.get());
        notifier.shutdown();
    }
}
