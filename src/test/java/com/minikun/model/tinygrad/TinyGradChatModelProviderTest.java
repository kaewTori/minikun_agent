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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

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
    void enablesToolCapabilityOnlyWhenConfigured() {
        HttpTinyGradClient client = client("test-model");

        TinyGradChatModelProvider provider = new TinyGradChatModelProvider(client, true);

        assertTrue(provider.capabilities().toolCalling());
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
        assertEquals(false, body.contains("\"thinking\""));
        assertEquals(false, body.contains("\"options\""));
    }

    @Test
    void mapsOpenAiToolsAndToolConversation() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            send(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                      {"id":"call-2","type":"function","function":{"name":"calculator.add","arguments":{"a":4,"b":5}}}
                    ]}}]}
                    """);
        });
        ToolCallback callback = callback("calculator.add", "Add two numbers",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}");
        AssistantMessage priorCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "calculator.add", "{\"a\":2,\"b\":3}")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "calculator.add", "{\"result\":5}")))
                .build();
        Prompt prompt = new Prompt(List.of(
                new org.springframework.ai.chat.messages.UserMessage("Add numbers"),
                priorCall,
                toolResponse),
                ToolCallingChatOptions.builder().toolCallbacks(callback).build());

        ChatResponse response = provider("test-model").chat(prompt);

        AssistantMessage.ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
        assertEquals("call-2", toolCall.id());
        assertEquals("calculator.add", toolCall.name());
        assertEquals("{\"a\":4,\"b\":5}", toolCall.arguments());
        var root = new ObjectMapper().readTree(requestBody.get());
        assertEquals("calculator.add", root.path("tools").path(0).path("function").path("name").asText());
        assertEquals("object", root.path("tools").path(0).path("function")
                .path("parameters").path("type").asText());
        assertEquals("call-1", root.path("messages").path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("tool", root.path("messages").path(2).path("role").asText());
        assertEquals("call-1", root.path("messages").path(2).path("tool_call_id").asText());
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
    void forceGreedyOverridesGlobalTemperatureForBatchedRuntime() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            send(exchange, 200, """
                    {"choices":[{"message":{"content":"answer"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """);
        });
        TinyGradChatModelProvider provider = new TinyGradChatModelProvider(client("test-model", true));

        provider.chat(new Prompt(new org.springframework.ai.chat.messages.UserMessage("Hello"),
                ChatOptions.builder().temperature(0.7).build()));

        assertEquals(0.0, new ObjectMapper().readTree(requestBody.get()).path("temperature").asDouble());
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
        TinyGradChatModelProvider provider = provider("test-model");

        List<String> content = provider.stream(new Prompt("Hello"))
                .map(response -> response.getResult().getOutput().getText())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("one", " two"), content);
        assertTrue(requestBody.get().contains("\"stream\":true"));
        assertTrue(requestBody.get().contains("\"stream_options\":{\"include_usage\":true}"));
    }

    @Test
    void mapsStreamingToolCallDelta() {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(readBody(exchange));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":[{\"delta\":{\"tool_calls\":["
                        + "{\"id\":\"call-stream\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"calculator.add\",\"arguments\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}}]}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });

        List<ChatResponse> responses = provider("test-model").stream(new Prompt("Add"))
                .collectList().block(Duration.ofSeconds(5));

        assertEquals(1, responses.size());
        AssistantMessage.ToolCall toolCall = responses.get(0).getResult().getOutput().getToolCalls().get(0);
        assertEquals("call-stream", toolCall.id());
        assertEquals("calculator.add", toolCall.name());
    }

    @Test
    void propagatesHttpErrors() {
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 503,
                "{\"error\":{\"message\":\"inference queue is full\"}}"));
        TinyGradChatModelProvider provider = provider("test-model");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> provider.chat(new Prompt("Hello")));

        assertEquals("TinyGrad request failed with HTTP status 503: inference queue is full", exception.getMessage());
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
        return new TinyGradChatModelProvider(client(model));
    }

    private HttpTinyGradClient client(String model) {
        return client(model, false);
    }

    private HttpTinyGradClient client(String model, boolean forceGreedy) {
        return new HttpTinyGradClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), baseUrl, model, Duration.ofSeconds(5), forceGreedy);
    }

    private ToolCallback callback(String name, String description, String schema) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
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
