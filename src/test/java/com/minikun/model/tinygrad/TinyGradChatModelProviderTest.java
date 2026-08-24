package com.minikun.model.tinygrad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.minikun.model.ChatModelId;

class TinyGradChatModelProviderTest {
    private HttpServer server;
    private AtomicReference<String> requestBody;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void exposesTinyGradIdAndConservativeCapabilities() {
        TinyGradChatModelProvider provider = provider("test-model");

        assertEquals(ChatModelId.TINYGRAD, provider.id());
        assertTrue(provider.capabilities().streaming());
        assertEquals(false, provider.capabilities().toolCalling());
        assertEquals(false, provider.capabilities().vision());
    }

    @Test
    void mapsSynchronousRequestAndResponse() {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            send(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"Hello back"}}]}
                    """);
        });
        TinyGradChatModelProvider provider = provider("test-model");

        ChatResponse response = provider.chat(new Prompt(List.of(
                new org.springframework.ai.chat.messages.SystemMessage("Be concise"),
                new org.springframework.ai.chat.messages.UserMessage("Hello"))));

        assertEquals("Hello back", response.getResult().getOutput().getText());
        String body = requestBody.get();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"content\":\"Be concise\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"Hello\""));
        assertTrue(body.contains("\"stream\":false"));
        assertTrue(body.contains("\"thinking\":false"));
        assertTrue(body.contains("\"options\":{\"num_ctx\":16384}"));
    }

    @Test
    void mapsGenerationOptionsAndUsage() {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            send(exchange, 200, """
                    {"choices":[{"message":{"content":"answer"}}],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                    """);
        });
        TinyGradChatModelProvider provider = provider("test-model");
        Prompt prompt = new Prompt(new org.springframework.ai.chat.messages.UserMessage("Hello"),
                ChatOptions.builder().temperature(0.7).maxTokens(512).stopSequences(List.of("END")).build());

        ChatResponse response = provider.chat(prompt);

        assertEquals("answer", response.getResult().getOutput().getText());
        assertEquals(12, response.getMetadata().getUsage().getPromptTokens());
        assertEquals(5, response.getMetadata().getUsage().getCompletionTokens());
        assertTrue(requestBody.get().contains("\"temperature\":0.7"));
        assertTrue(requestBody.get().contains("\"max_tokens\":512"));
        assertTrue(requestBody.get().contains("\"stop\":[\"END\"]"));
    }

    @Test
    void rejectsReasoningOnlyResponseWithoutAssistantContent() {
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 200, """
                {"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"Thinking Process"}}],"finish_reason":"length"}
                """));
        TinyGradChatModelProvider provider = provider("test-model");

        assertThrows(IllegalStateException.class, () -> provider.chat(new Prompt("Hello")));
    }

    @Test
    void mapsStreamingUsageEventWithoutEmittingText() {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                output.write("data: {\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3,\"total_tokens\":12}}\n\n".getBytes(StandardCharsets.UTF_8));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        TinyGradChatModelProvider provider = provider("test-model");

        List<ChatResponse> responses = provider.stream(new Prompt("Hello"))
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(2, responses.size());
        assertEquals("answer", responses.get(0).getResult().getOutput().getText());
        assertEquals(9, responses.get(1).getMetadata().getUsage().getPromptTokens());
        assertEquals("", responses.get(1).getResult().getOutput().getText());
    }

    @Test
    void mapsOrderedStreamingEventsAndDone() {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        TinyGradChatModelProvider provider = provider("test-model", 8192);

        List<String> content = provider.stream(new Prompt("Hello"))
                .map(response -> response.getResult().getOutput().getText())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("one", " two"), content);
        assertTrue(requestBody.get().contains("\"stream\":true"));
        assertTrue(requestBody.get().contains("\"options\":{\"num_ctx\":8192}"));
    }

    @Test
    void propagatesHttpErrors() {
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 503, "{}"));
        TinyGradChatModelProvider provider = provider("test-model");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> provider.chat(new Prompt("Hello")));

        assertEquals("TinyGrad request failed with HTTP status 503", exception.getMessage());
    }

    @Test
    void rejectsMalformedResponses() {
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 200, "{}"));
        TinyGradChatModelProvider provider = provider("test-model");

        assertThrows(IllegalStateException.class, () -> provider.chat(new Prompt("Hello")));
    }

    @Test
    void validatesMissingModelAtInvocation() {
        TinyGradChatModelProvider provider = provider("");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> provider.chat(new Prompt("Hello")));

        assertEquals("TinyGrad model must be configured before invocation", exception.getMessage());
    }

    private TinyGradChatModelProvider provider(String model) {
        return provider(model, 16384);
    }

    private TinyGradChatModelProvider provider(String model, int contextSize) {
        HttpTinyGradClient client = new HttpTinyGradClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), baseUrl, model, Duration.ofSeconds(5), contextSize);
        return new TinyGradChatModelProvider(client);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
