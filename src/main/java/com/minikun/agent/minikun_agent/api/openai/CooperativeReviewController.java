package com.minikun.agent.minikun_agent.api.openai;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minikun.model.CooperativeReviewStore;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/v1/cooperation/reviews")
@RequiredArgsConstructor
public class CooperativeReviewController {
    private final CooperativeReviewStore store;

    @GetMapping("/{conversationId}")
    public ResponseEntity<CooperativeReviewStore.Review> review(@PathVariable String conversationId) {
        CooperativeReviewStore.Review review = store.find(conversationId);
        return review == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(review);
    }

    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<CooperativeReviewStore.Review>> events(@PathVariable String conversationId) {
        return store.events(conversationId)
                .takeUntil(review -> "COMPLETED".equals(review.status()) || "FAILED".equals(review.status()))
                .map(review -> ServerSentEvent.<CooperativeReviewStore.Review>builder()
                        .event("cooperative-review")
                        .data(review)
                        .build());
    }
}
