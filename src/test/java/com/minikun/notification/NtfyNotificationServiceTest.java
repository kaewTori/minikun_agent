package com.minikun.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class NtfyNotificationServiceTest {
    private HttpServer server;
    private AtomicReference<String> body;
    private AtomicReference<String> title;
    private AtomicReference<String> authorization;
    private AtomicReference<String> click;
    private AtomicReference<String> actions;

    @BeforeEach
    void startServer() throws Exception {
        body = new AtomicReference<>();
        title = new AtomicReference<>();
        authorization = new AtomicReference<>();
        click = new AtomicReference<>();
        actions = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reminder", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void publishesToReminderTopicWithNtfyHeaders() {
        String topic = "http://127.0.0.1:" + server.getAddress().getPort() + "/reminder";
        NtfyNotificationService service = new NtfyNotificationService(
                true, Duration.ofSeconds(2), "test-token", topic);

        service.publish(NotificationChannel.REMINDER, "Mini-kun reminder", "พี่สาวครับ ถึงเวลาแล้ว",
                4, "bell,calendar");

        assertEquals("พี่สาวครับ ถึงเวลาแล้ว", body.get());
        assertEquals("Mini-kun reminder", title.get());
        assertEquals("Bearer test-token", authorization.get());
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        title.set(exchange.getRequestHeaders().getFirst("Title"));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        click.set(exchange.getRequestHeaders().getFirst("Click"));
        actions.set(exchange.getRequestHeaders().getFirst("Actions"));
        byte[] response = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    @Test
    void addsAThaiReplyButtonWithoutChangingTheInlineMessage() {
        String topic = "http://127.0.0.1:" + server.getAddress().getPort() + "/reminder";
        var service = new NtfyNotificationService(true, Duration.ofSeconds(2), "", topic);
        String url = "https://mini-kun:8443/cockpit/?view=chat&reply_symbol=SCHD";
        service.publish(NotificationChannel.REMINDER, "Investment", "สรุปข่าวครับ", 3, "investment", url);
        assertEquals("สรุปข่าวครับ", body.get());
        assertEquals(url, click.get());
        String decoded = new String(java.util.Base64.getDecoder().decode(
                actions.get().substring("=?UTF-8?B?".length(), actions.get().length() - 2)), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("view, ตอบมินิคุง, " + url, decoded);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new NotificationRequest("INVESTMENT", "default", NotificationChannel.REMINDER,
                        "title", "body", 3, "", "javascript:alert(1)"));
    }
}
