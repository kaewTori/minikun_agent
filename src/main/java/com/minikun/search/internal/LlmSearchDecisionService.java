package com.minikun.search.internal;

import com.minikun.search.SearchDecisionProvider;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_LIMIT = 256;

    private final SearchDecisionProvider client;
    private final SearchDecisionService fallback;
    private final Clock clock;
    private final SearchDecisionPromptBuilder promptBuilder;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE);
    private final ConcurrentHashMap<String, CachedDecision> cache = new ConcurrentHashMap<>();

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
        return decideInternal(query, null);
    }

    @Override
    public SearchDecision decide(String query, String conversationContext) {
        return decideInternal(query, conversationContext);
    }

    private SearchDecision decideInternal(String query, String conversationContext) {
        Timer.Sample sample = startTimer();
        SearchDecision decision = null;
        try {
            String cacheKey = cacheKey(query, conversationContext);
            CachedDecision cached = cache.get(cacheKey);
            Instant now = clock.instant();
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return new SearchDecision(
                        cached.shouldSearch(), query.trim(), cached.reason(), cached.planHints());
            }
            SearchDecisionPrompt prompt = conversationContext == null
                    ? promptBuilder.build(LocalDate.now(clock), query)
                    : promptBuilder.build(LocalDate.now(clock), query, conversationContext);
            SearchDecision classified = client.classify(prompt);
            decision = new SearchDecision(
                    classified.shouldSearch(), query.trim(), classified.reason(), classified.planHints());
            if (cache.size() >= CACHE_LIMIT) cache.clear();
            cache.put(cacheKey, new CachedDecision(
                    decision.shouldSearch(), decision.reason(), decision.planHints(), now.plus(CACHE_TTL)));
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

    private String cacheKey(String query, String context) {
        return (query + "\n" + (context == null ? "" : context))
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record CachedDecision(
            boolean shouldSearch, SearchDecisionReason reason,
            com.minikun.search.model.SearchPlanHints planHints, Instant expiresAt) { }

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
