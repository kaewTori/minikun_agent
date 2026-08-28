package com.minikun.model;

import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Short-lived in-memory store for asynchronous model reviews. */
@Service
public final class CooperativeReviewStore {
    private final Map<String, Review> reviews = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<Review>> streams = new ConcurrentHashMap<>();
    private final Queue<String> reviewOrder = new ConcurrentLinkedQueue<>();
    private final Queue<String> streamOrder = new ConcurrentLinkedQueue<>();
    private final int maximumEntries;

    public CooperativeReviewStore() {
        this(1_000);
    }

    @Autowired
    public CooperativeReviewStore(
            @Value("${minikun.model.cooperation.review-store.maximum-entries:1000}") int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximum review entries must be positive");
        this.maximumEntries = maximumEntries;
    }

    public void pending(String conversationId, String draft) {
        publish(new Review(conversationId, "PENDING", draft, null, null, Instant.now()));
    }

    public void completed(String conversationId, String draft, String revised) {
        publish(new Review(conversationId, "COMPLETED", draft, revised, null, Instant.now()));
    }

    public void rejected(String conversationId, String draft, String revised, String reason) {
        publish(new Review(conversationId, "REJECTED", draft, revised, reason, Instant.now()));
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
        if (reviews.put(review.conversationId(), review) == null) {
            reviewOrder.add(review.conversationId());
            trimReviews();
        }
        streamFor(review.conversationId()).tryEmitNext(review);
    }

    private Sinks.Many<Review> streamFor(String conversationId) {
        Sinks.Many<Review> existing = streams.get(conversationId);
        if (existing != null) return existing;
        Sinks.Many<Review> created = Sinks.many().replay().latest();
        existing = streams.putIfAbsent(conversationId, created);
        if (existing != null) return existing;
        streamOrder.add(conversationId);
        trimStreams();
        return created;
    }

    private void trimReviews() {
        while (reviews.size() > maximumEntries) {
            String oldest = reviewOrder.poll();
            if (oldest == null) return;
            reviews.remove(oldest);
        }
    }

    private void trimStreams() {
        while (streams.size() > maximumEntries) {
            String oldest = streamOrder.poll();
            if (oldest == null) return;
            Sinks.Many<Review> evicted = streams.remove(oldest);
            if (evicted != null) evicted.tryEmitComplete();
        }
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
