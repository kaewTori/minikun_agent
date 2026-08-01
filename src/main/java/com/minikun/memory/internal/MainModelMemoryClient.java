package com.minikun.memory.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.ollama.api.OllamaApi;

import lombok.extern.slf4j.Slf4j;

import com.minikun.memory.MemoryException;
import com.minikun.memory.MemoryExtractionClient;
import com.minikun.memory.MemoryPolicy;
import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;

@Slf4j
final class MainModelMemoryClient implements MemoryExtractionClient {
    private final OllamaApi ollamaApi;
    private final String model;
    private final Duration timeout;
    private final Clock clock;
    private final MemoryPromptBuilder promptBuilder;
    private final MemoryResponseParser responseParser;
    private final MemoryValidator validator;
    private final MemoryPolicy policy;

    MainModelMemoryClient(OllamaApi ollamaApi, String model, Duration timeout, Clock clock,
            MemoryPromptBuilder promptBuilder, MemoryResponseParser responseParser,
            MemoryValidator validator, MemoryPolicy policy) {
        this.ollamaApi = ollamaApi;
        this.model = model;
        this.timeout = timeout;
        this.clock = clock;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.validator = validator;
        this.policy = policy;
    }

    @Override
    public List<CandidateMemory> extractConversationMemories(CompletedConversation conversation) {
        String prompt = promptBuilder.build(conversation, clock.instant());
        long started = System.nanoTime();
        try {
            String response = CompletableFuture.supplyAsync(() -> ollamaApi.chat(
                    OllamaApi.ChatRequest.builder(model)
                            .messages(List.of(new OllamaApi.Message(
                                    OllamaApi.Message.Role.USER, prompt, List.of(), List.of(), null, null)))
                            .stream(false)
                            .format("json")
                            .options(java.util.Map.of("temperature", 0.0, "num_predict", 1024))
                            .build())
                    .message()
                    .content())
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .get();
            if (response == null || response.isBlank()) {
                throw new MemoryException("main model returned an empty response");
            }
            var validation = validator.validate(responseParser.parse(response), policy);
            log.info("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} duration_ms={} extraction_success=true provider=main_model",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), elapsedMillis(started));
            return validation.valid();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(conversation, started, "main model memory extraction was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() instanceof CompletionException completion
                    ? completion.getCause() : exception.getCause();
            if (cause instanceof TimeoutException) {
                throw failure(conversation, started, "main model memory extraction timed out after " + timeout, cause);
            }
            if (cause instanceof MemoryException memoryException) {
                throw memoryException;
            }
            throw failure(conversation, started, "main model memory extraction failed", cause);
        } catch (MemoryException exception) {
            log.warn("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} extraction_success=false provider=main_model",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(conversation, started, "main model memory extraction failed", exception);
        }
    }

    private MemoryException failure(CompletedConversation conversation, long started,
            String message, Throwable cause) {
        log.warn("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} duration_ms={} extraction_success=false provider=main_model",
                conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), elapsedMillis(started), cause);
        return new MemoryException(message, cause);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
