package com.minikun.personalloop;

import com.minikun.personality.feedback.ChatFeedback;
import com.minikun.personality.feedback.ChatQualityFeedbackObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;

/** Correlates feedback with the stored turn trace without retaining prompt or response text. */
public final class TurnQualityFeedbackMetrics implements ChatQualityFeedbackObserver {
    static final String METRIC = "minikun.chat.quality.feedback";
    private final PersonalLoopStore store;
    private final MeterRegistry metrics;

    public TurnQualityFeedbackMetrics(PersonalLoopStore store, MeterRegistry metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public void observe(ChatFeedback feedback) {
        if (feedback == null || metrics == null) return;
        Map<String, Object> decisions = store.trace(feedback.ownerId(), feedback.messageId())
                .map(PersonalLoopModels.ExplainabilityTrace::decisions).orElse(Map.of());
        try {
            Counter.builder(METRIC)
                    .description("User feedback correlated with the resolved turn route")
                    .tag("rating", feedback.rating().toLowerCase(Locale.ROOT))
                    .tag("category", feedback.category().name().toLowerCase(Locale.ROOT))
                    .tag("intent", tag(decisions.get("turn_intent")))
                    .tag("execution", tag(decisions.get("turn_execution")))
                    .tag("profile", tag(decisions.get("generation_profile")))
                    .register(metrics).increment();
        } catch (RuntimeException ignored) {
            // Quality reporting must never affect feedback persistence.
        }
    }

    private String tag(Object value) {
        String normalized = value == null ? "unknown" : value.toString().toLowerCase(Locale.ROOT).trim();
        return normalized.matches("[a-z0-9_-]{1,40}") ? normalized : "unknown";
    }
}
