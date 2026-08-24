package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeTypeUtils;

import reactor.core.publisher.Flux;

class FailoverChatModelProviderTest {
    @Test
    void fallsBackForRetryableSynchronousFailure() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD);
        primary.chatFailure = new ModelProviderException("queue full", true);
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING);
        fallback.chatResponse = response("ollama");
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        ChatResponse result = provider.chat(new Prompt("hello"));

        assertEquals("ollama", result.getResult().getOutput().getText());
        assertEquals(1, fallback.chatCalls.get());
        assertEquals(ChatModelId.TINYGRAD, provider.id());
    }

    @Test
    void adaptsPortableToolOptionsForOllamaFallback() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD);
        primary.chatFailure = new ModelProviderException("queue full", true);
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING);
        fallback.chatResponse = response("ollama");
        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("calculator.add").description("add")
                    .inputSchema("{\"type\":\"object\"}").build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return "42"; }
        };
        Prompt prompt = new Prompt("hello", ToolCallingChatOptions.builder()
                .model("tinygrad-only-model").temperature(0.2)
                .toolCallbacks(callback).toolContext(Map.of("ownerId", "owner")).build());
        FailoverChatModelProvider provider = new FailoverChatModelProvider(
                primary, fallback, null, "ollama-fallback-model");

        provider.chat(prompt);

        assertEquals(true, fallback.lastPrompt.getOptions() instanceof OllamaChatOptions);
        OllamaChatOptions options = (OllamaChatOptions) fallback.lastPrompt.getOptions();
        assertEquals("ollama-fallback-model", options.getModel());
        assertEquals(0.2, options.getTemperature());
        assertEquals(1, options.getToolCallbacks().size());
        assertEquals("owner", options.getToolContext().get("ownerId"));
    }

    @Test
    void exposesNonRetryableFailureWithoutFallback() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD);
        primary.chatFailure = new ModelProviderException("context exceeded", false);
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING);
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        assertThrows(ModelProviderException.class, () -> provider.chat(new Prompt("hello")));

        assertEquals(0, fallback.chatCalls.get());
    }

    @Test
    void fallsBackOnlyWhenStreamFailsBeforeFirstChunk() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD);
        primary.streamResponse = Flux.error(new ModelProviderException("unavailable", true));
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING);
        fallback.streamResponse = Flux.just(response("ollama"));
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        List<String> result = provider.stream(new Prompt("hello"))
                .map(value -> value.getResult().getOutput().getText())
                .collectList().block();

        assertEquals(List.of("ollama"), result);
        assertEquals(1, fallback.streamCalls.get());
    }

    @Test
    void neverMixesFallbackIntoPartiallyEmittedStream() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD);
        primary.streamResponse = Flux.concat(
                Flux.just(response("partial")),
                Flux.error(new ModelProviderException("late failure", true)));
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING);
        fallback.streamResponse = Flux.just(response("must not appear"));
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        List<String> emitted = new java.util.ArrayList<>();
        assertThrows(ModelProviderException.class, () -> provider.stream(new Prompt("hello"))
                .doOnNext(value -> emitted.add(value.getResult().getOutput().getText()))
                .collectList().block());

        assertEquals(List.of("partial"), emitted);
        assertEquals(0, fallback.streamCalls.get());
    }

    @Test
    void routesVisionDirectlyToCapableFallbackWithoutLeakingTinyGradModelName() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD,
                new ModelCapabilities(true, true, false));
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING,
                new ModelCapabilities(true, true, true));
        fallback.chatResponse = response("vision through ollama");
        UserMessage user = UserMessage.builder()
                .text("describe")
                .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 })))
                .build();
        Prompt prompt = new Prompt(List.of(user), ChatOptions.builder()
                .model("tinygrad-only-model").temperature(0.0).build());
        FailoverChatModelProvider provider = new FailoverChatModelProvider(
                primary, fallback, null, "ollama-vision-model");

        ChatResponse result = provider.chat(prompt);

        assertEquals("vision through ollama", result.getResult().getOutput().getText());
        assertEquals(0, primary.chatCalls.get());
        assertEquals(1, fallback.chatCalls.get());
        assertEquals(1, fallback.lastPrompt.getUserMessage().getMedia().size());
        OllamaChatOptions options = (OllamaChatOptions) fallback.lastPrompt.getOptions();
        assertEquals("ollama-vision-model", options.getModel());
        assertEquals(true, provider.capabilities().vision());
    }

    @Test
    void routesToolsDirectlyWhenOnlyFallbackSupportsToolCalling() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD,
                new ModelCapabilities(true, false, false));
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING,
                new ModelCapabilities(true, true, true));
        fallback.chatResponse = response("tool through ollama");
        ToolCallback callback = toolCallback();
        Prompt prompt = new Prompt("use tool", ToolCallingChatOptions.builder()
                .toolCallbacks(callback).build());
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        provider.chat(prompt);

        assertEquals(0, primary.chatCalls.get());
        assertEquals(1, fallback.chatCalls.get());
        assertEquals(true, provider.capabilities().toolCalling());
    }

    @Test
    void routesStreamingDirectlyWhenOnlyFallbackSupportsStreaming() {
        StubProvider primary = new StubProvider(ChatModelId.TINYGRAD,
                new ModelCapabilities(false, true, false));
        StubProvider fallback = new StubProvider(ChatModelId.EXISTING,
                new ModelCapabilities(true, true, true));
        fallback.streamResponse = Flux.just(response("stream through ollama"));
        FailoverChatModelProvider provider = new FailoverChatModelProvider(primary, fallback, null);

        List<String> result = provider.stream(new Prompt("hello"))
                .map(value -> value.getResult().getOutput().getText())
                .collectList().block();

        assertEquals(List.of("stream through ollama"), result);
        assertEquals(0, primary.streamCalls.get());
        assertEquals(1, fallback.streamCalls.get());
        assertEquals(true, provider.capabilities().streaming());
    }

    private static ToolCallback toolCallback() {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name("calculator.add").description("add")
                    .inputSchema("{\"type\":\"object\"}").build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { return "42"; }
        };
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class StubProvider implements ChatModelProvider {
        private final ChatModelId id;
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final AtomicInteger streamCalls = new AtomicInteger();
        private final ModelCapabilities capabilities;
        private ChatResponse chatResponse;
        private RuntimeException chatFailure;
        private Flux<ChatResponse> streamResponse = Flux.empty();
        private Prompt lastPrompt;

        private StubProvider(ChatModelId id) {
            this(id, new ModelCapabilities(true, false, false));
        }

        private StubProvider(ChatModelId id, ModelCapabilities capabilities) {
            this.id = id;
            this.capabilities = capabilities;
        }

        @Override public ChatModelId id() { return id; }
        @Override public ModelCapabilities capabilities() { return capabilities; }

        @Override
        public ChatResponse chat(Prompt prompt) {
            chatCalls.incrementAndGet();
            lastPrompt = prompt;
            if (chatFailure != null) throw chatFailure;
            return chatResponse;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
            lastPrompt = prompt;
            return streamResponse;
        }
    }
}
