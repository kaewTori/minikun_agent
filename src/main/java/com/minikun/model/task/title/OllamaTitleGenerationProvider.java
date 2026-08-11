package com.minikun.model.task.title;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.ollama.api.OllamaApi;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class OllamaTitleGenerationProvider implements TitleGenerationProvider {
    private final OllamaApi ollamaApi;
    private final String model;
    private final Duration timeout;
    private final TitlePromptBuilder promptBuilder;

    public OllamaTitleGenerationProvider(OllamaApi ollamaApi, String model, Duration timeout,
            TitlePromptBuilder promptBuilder) {
        this.ollamaApi = Objects.requireNonNull(ollamaApi, "ollamaApi must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    @Override
    public String generateTitle(List<ChatMessage> messages) {
        String prompt = promptBuilder.build(messages);
        try {
            String response = CompletableFuture.supplyAsync(() -> ollamaApi.chat(
                    OllamaApi.ChatRequest.builder(model)
                            .messages(List.of(new OllamaApi.Message(
                                    OllamaApi.Message.Role.USER, prompt, List.of(), List.of(), null, null)))
                            .stream(false)
                            .options(Map.of("temperature", 0.0, "num_predict", 32))
                            .build())
                    .message()
                    .content())
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .get();
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Ollama returned an empty title");
            }
            return response.trim().replaceAll("^\\\"|\\\"$", "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("title generation was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() instanceof CompletionException completion
                    ? completion.getCause() : exception.getCause();
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("title generation timed out", cause);
            }
            throw new IllegalStateException("title generation failed", cause);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("title generation failed", exception);
        }
    }
}
