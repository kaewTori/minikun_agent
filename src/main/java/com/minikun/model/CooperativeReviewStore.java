package com.minikun.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Short-lived in-memory store for asynchronous model reviews. */
@Service
public final class CooperativeReviewStore {
    private final Map<String, Review> reviews = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<Review>> streams = new ConcurrentHashMap<>();

    public void pending(String conversationId, String draft) {
        publish(new Review(conversationId, "PENDING", draft, null, null, Instant.now()));
    }

    public void completed(String conversationId, String draft, String revised) {
        publish(new Review(conversationId, "COMPLETED", draft, revised, null, Instant.now()));
    }

    public void failed(String conversationId, String draft, String reason) {
        publish(new Review(conversationId, "FAILED", draft, null, reason, Instant.now()));
    }

    public Review find(String conversationId) {
        return reviews.get(conversationId);
    }

    public Flux<Review> events(String conversationId) {
        return streamFor(conversationId).asFlux();
    }

    private void publish(Review review) {
        reviews.put(review.conversationId(), review);
        streamFor(review.conversationId()).tryEmitNext(review);
    }

    private Sinks.Many<Review> streamFor(String conversationId) {
        return streams.computeIfAbsent(conversationId, ignored -> Sinks.many().replay().latest());
    }

    public record Review(
            String conversationId,
            String status,
            String draft,
            String revised,
            String error,
            Instant updatedAt) {
    }
}
