package com.minikun.agent.minikun_agent.api.openai;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/** Coordinates the single bounded repair attempt for length-limited responses. */
final class ChatResponseContinuationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatResponseContinuationCoordinator.class);

    private final ResponseContinuation continuation;
    private final ChatPerformanceMetrics metrics;

    ChatResponseContinuationCoordinator(ResponseContinuation continuation, ChatPerformanceMetrics metrics) {
        this.continuation = continuation;
        this.metrics = metrics;
    }

    BlockingResult complete(
            ChatResponse primary,
            String content,
            Prompt originalPrompt,
            String profile,
            String requestId,
            Function<Prompt, ChatResponse> generator) {
        String finishReason = finishReasonOrStop(primary);
        if (!hasText(content) || !continuation.shouldContinue(primary)) {
            return new BlockingResult(content, finishReason, null, 0);
        }
        record(profile, "started");
        log.info("process=response_continuation event=started request_id={} profile={} stream=false",
                requestId, profile);
        try {
            ChatResponse continued = generator.apply(
                    continuation.continuationPrompt(originalPrompt, content, profile));
            String continuedText = responseText(continued);
            if (!hasText(continuedText)) {
                record(profile, "empty");
                log.warn("process=response_continuation event=empty request_id={} stream=false", requestId);
                return new BlockingResult(content, finishReason, continued, 1);
            }
            record(profile, "completed");
            log.info("process=response_continuation event=completed request_id={} stream=false", requestId);
            return new BlockingResult(
                    continuation.appendWithoutRepeating(content, continuedText),
                    finishReasonOrStop(continued), continued, 1);
        } catch (RuntimeException exception) {
            record(profile, "failed");
            log.warn("process=response_continuation event=failed request_id={} stream=false reason={}",
                    requestId, exception.getMessage());
            return new BlockingResult(content, finishReason, null, 1);
        }
    }

    StreamingResult stream(
            Flux<ChatResponse> primary,
            StringBuilder assistantContent,
            Prompt originalPrompt,
            String profile,
            String requestId,
            Function<Prompt, Flux<ChatResponse>> generator,
            Consumer<ChatResponse> primaryObserver,
            Consumer<ChatResponse> continuationObserver) {
        AtomicBoolean primaryLengthLimited = new AtomicBoolean();
        AtomicInteger continuationCount = new AtomicInteger();
        AtomicReference<String> finishReason = new AtomicReference<>("stop");
        Flux<ChatResponse> observedPrimary = primary.doOnNext(response -> {
            primaryObserver.accept(response);
            captureFinishReason(finishReason, response);
            if (continuation.shouldContinue(response)) {
                primaryLengthLimited.set(true);
            }
        });
        Flux<ChatResponse> responses = observedPrimary.concatWith(Flux.defer(() -> {
            if (!primaryLengthLimited.get() || assistantContent.isEmpty()) {
                return Flux.empty();
            }
            continuationCount.incrementAndGet();
            record(profile, "started");
            log.info("process=response_continuation event=started request_id={} profile={} stream=true",
                    requestId, profile);
            AtomicBoolean producedText = new AtomicBoolean();
            Prompt continuationPrompt = continuation.continuationPrompt(
                    originalPrompt, assistantContent.toString(), profile);
            return continuation.bufferedDelta(generator.apply(continuationPrompt), assistantContent.toString())
                    .doOnNext(response -> {
                        producedText.set(true);
                        continuationObserver.accept(response);
                        captureFinishReason(finishReason, response);
                    })
                    .doOnComplete(() -> {
                        String result = producedText.get() ? "completed" : "empty";
                        record(profile, result);
                        log.info("process=response_continuation event={} request_id={} stream=true", result, requestId);
                    })
                    .onErrorResume(exception -> {
                        record(profile, "failed");
                        log.warn("process=response_continuation event=failed request_id={} stream=true reason={}",
                                requestId, exception.getMessage());
                        return Flux.empty();
                    });
        }));
        return new StreamingResult(responses, finishReason, continuationCount);
    }

    private void captureFinishReason(AtomicReference<String> target, ChatResponse response) {
        String finishReason = continuation.finishReason(response);
        if (!finishReason.isBlank()) {
            target.set(finishReason);
        }
    }

    private String finishReasonOrStop(ChatResponse response) {
        String finishReason = continuation.finishReason(response);
        return finishReason.isBlank() ? "stop" : finishReason;
    }

    private String responseText(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void record(String profile, String result) {
        if (metrics != null) {
            metrics.continuation(profile, result);
        }
    }

    record BlockingResult(
            String content, String finishReason, ChatResponse continuationResponse, int continuationCount) {
    }

    record StreamingResult(
            Flux<ChatResponse> responses,
            AtomicReference<String> finishReason,
            AtomicInteger continuationCount) {
    }
}
