package com.minikun.memory.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;
import com.minikun.memory.MemoryExtractionClient;
import com.minikun.memory.MemoryPolicy;
import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;

@Slf4j
final class LlamaCppClient implements MemoryExtractionClient {
    private final RestClient restClient;
    private final String model;
    private final Duration timeout;
    private final Clock clock;
    private final MemoryPromptBuilder promptBuilder;
    private final MemoryResponseParser responseParser;
    private final MemoryValidator validator;
    private final MemoryPolicy policy;

    LlamaCppClient(RestClient restClient, String model, Duration timeout, Clock clock,
            MemoryPromptBuilder promptBuilder, MemoryResponseParser responseParser,
            MemoryValidator validator, MemoryPolicy policy) {
        this.restClient = restClient;
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
            Response response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(model, List.of(new Message("user", prompt)), false,
                            1024, 0.0, new ResponseFormat("json_object")))
                    .retrieve()
                    .body(Response.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null) {
                throw new MemoryException("llama.cpp returned an empty response");
            }
            var validation = validator.validate(
                    responseParser.parse(response.choices().getFirst().message().content()), policy);
            log.info("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} duration_ms={} extraction_success=true",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), elapsedMillis(started));
            return validation.valid();
        } catch (ResourceAccessException exception) {
            log.warn("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} extraction_success=false failure=timeout_or_access",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), exception);
            throw new MemoryException("llama.cpp request timed out or was inaccessible after " + timeout, exception);
        } catch (MemoryException exception) {
            log.warn("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} extraction_success=false",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("memory_llm_call conversation_id={} prompt_version={} model={} timeout_ms={} extraction_success=false",
                    conversation.conversationId(), promptBuilder.version(), model, timeout.toMillis(), exception);
            throw new MemoryException("llama.cpp memory extraction failed", exception);
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record Request(String model, List<Message> messages, boolean stream, int max_tokens,
            double temperature, ResponseFormat response_format) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    private record Response(List<Choice> choices) {
    }

    private record Choice(MessageResponse message) {
    }

    private record MessageResponse(String role, String content) {
    }
}
