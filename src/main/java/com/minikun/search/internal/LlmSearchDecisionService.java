package com.minikun.search.internal;

import com.minikun.search.SearchDecisionProvider;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.time.Clock;
import java.time.LocalDate;
import com.minikun.search.model.SearchDecisionPrompt;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LlmSearchDecisionService implements SearchDecisionService {
    private static final long WARNING_INTERVAL_NANOS = 60_000_000_000L;
    private static final String DECISION_TIMER = "minikun.search.decision.duration";
    private static final String NO_SEARCH_COUNTER = "minikun.search.quality.no_search";

    private final SearchDecisionProvider client;
    private final SearchDecisionService fallback;
    private final Clock clock;
    private final SearchDecisionPromptBuilder promptBuilder;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE);

    public LlmSearchDecisionService(
            SearchDecisionProvider client, SearchDecisionService fallback, Clock clock,
            SearchDecisionPromptBuilder promptBuilder, MeterRegistry meterRegistry) {
        this.client = client;
        this.fallback = fallback;
        this.clock = clock;
        this.promptBuilder = promptBuilder;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SearchDecision decide(String query) {
        Timer.Sample sample = startTimer();
        SearchDecision decision = null;
        try {
            SearchDecisionPrompt prompt = promptBuilder.build(LocalDate.now(clock), query);
            decision = client.classify(prompt);
            return decision;
        } catch (RuntimeException exception) {
            warnOnce(exception);
            SearchDecision ruleDecision = fallback.decide(query);
            decision = new SearchDecision(
                    ruleDecision.shouldSearch(), ruleDecision.query(), SearchDecisionReason.RULE_FALLBACK);
            return decision;
        } finally {
            recordDecision(sample);
            recordQuality(decision);
            logDecision(decision);
        }
    }

    private Timer.Sample startTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void recordDecision(Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(meterRegistry.timer(DECISION_TIMER, "mode", "LLM"));
        } catch (RuntimeException ignored) {
            // Observability must not affect decision execution.
        }
    }

    private void logDecision(SearchDecision decision) {
        if (decision == null) {
            return;
        }
        try {
            log.debug("Search decision mode=LLM shouldSearch={} reason={}",
                    decision.shouldSearch(), decision.reason());
        } catch (RuntimeException ignored) {
            // Logging must not affect decision execution.
        }
    }

    private void recordQuality(SearchDecision decision) {
        if (decision == null || decision.shouldSearch() || meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(NO_SEARCH_COUNTER).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect decision execution.
        }
    }

    private void warnOnce(RuntimeException exception) {
        long now = System.nanoTime();
        long previous = lastWarning.get();
        if (now - previous >= WARNING_INTERVAL_NANOS && lastWarning.compareAndSet(previous, now)) {
            log.warn("LLM search classification failed; using rule fallback", exception);
        }
    }
}