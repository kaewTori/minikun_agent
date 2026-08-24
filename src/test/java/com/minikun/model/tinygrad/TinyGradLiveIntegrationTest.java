package com.minikun.model.tinygrad;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.fasterxml.jackson.databind.ObjectMapper;

class TinyGradLiveIntegrationTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    @Test
    void javaClientUsesLiveGreedyBatchAndStreamingRuntime() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                System.getenv().getOrDefault("MINIKUN_TINYGRAD_LIVE_TEST", "false")));
        String baseUrl = System.getenv().getOrDefault(
                "MINIKUN_TINYGRAD_BASE_URL", "http://127.0.0.1:8001/v1");
        String model = System.getenv().getOrDefault(
                "MINIKUN_TINYGRAD_MODEL", "gemma-4-E4B-it-ultra-uncensored-heretic-Q8_0");
        HttpTinyGradClient client = new HttpTinyGradClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(), baseUrl, model, REQUEST_TIMEOUT, true);

        ChatOptions options = ChatOptions.builder().temperature(0.7).maxTokens(12).build();
        List<CompletableFuture<ChatResponse>> calls = new ArrayList<>();
        for (String marker : List.of("A", "B", "C", "D")) {
            Prompt prompt = new Prompt(new UserMessage("Reply briefly with marker " + marker + "."), options);
            calls.add(CompletableFuture.supplyAsync(() -> client.chat(prompt)));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        for (CompletableFuture<ChatResponse> call : calls) {
            ChatResponse response = call.join();
            assertFalse(response.getResult().getOutput().getText().isBlank());
            assertNotNull(response.getMetadata().getUsage());
            assertTrue(response.getMetadata().getUsage().getCompletionTokens() > 0);
        }

        Prompt streamingPrompt = new Prompt(new UserMessage("Reply briefly with marker STREAM."), options);
        List<ChatResponse> chunks = client.stream(streamingPrompt)
                .collectList()
                .block(REQUEST_TIMEOUT);
        assertNotNull(chunks);
        assertTrue(chunks.stream().anyMatch(response -> !response.getResult().getOutput().getText().isBlank()));
        assertTrue(chunks.stream().anyMatch(response -> response.getMetadata().getUsage() != null
                && response.getMetadata().getUsage().getCompletionTokens() > 0));
    }
}
