package com.minikun.personalloop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.personality.feedback.ChatFeedback;
import com.minikun.personality.feedback.ChatFeedbackCategory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnQualityFeedbackMetricsTest {
    @Test
    void feedbackIsCorrelatedWithTheStoredTurnRoute() {
        PersonalLoopStore store = new PersonalLoopStore(null, new ObjectMapper());
        store.save(new PersonalLoopModels.ExplainabilityTrace(UUID.randomUUID(), "default", "chat-1", "response-1",
                "trace", List.of(), List.of(), Map.of(
                        "turn_intent", "technical", "turn_execution", "direct_stream",
                        "generation_profile", "technical"), Instant.parse("2026-09-05T10:00:00Z")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TurnQualityFeedbackMetrics observer = new TurnQualityFeedbackMetrics(store, registry);

        observer.observe(new ChatFeedback(UUID.randomUUID(), "default", "chat-1", "response-1",
                "DOWN", ChatFeedbackCategory.FACT_WRONG, "ข้อมูลผิด", Instant.parse("2026-09-05T10:01:00Z")));

        assertEquals(1.0, registry.get(TurnQualityFeedbackMetrics.METRIC)
                .tags("rating", "down", "category", "fact_wrong", "intent", "technical",
                        "execution", "direct_stream", "profile", "technical")
                .counter().count());
    }
}
