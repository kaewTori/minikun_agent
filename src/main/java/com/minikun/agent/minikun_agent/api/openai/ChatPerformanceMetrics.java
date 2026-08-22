package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.minikun.model.ModelPerformanceMetrics;

/** Low-cardinality latency metrics for the user-visible chat pipeline. */
@Component
public final class ChatPerformanceMetrics implements ModelPerformanceMetrics {
    static final String STAGE_DURATION = "minikun.chat.stage.duration";
    static final String FAST_PATH_REQUESTS = "minikun.chat.fast_path.requests";
    static final String GENERATION_PROFILES = "minikun.chat.generation.profile.requests";
    static final String TOKENS = "minikun.chat.generation.tokens";
    static final String TOKENS_PER_SECOND = "minikun.chat.generation.tokens.per.second";

    private final MeterRegistry meterRegistry;

    public ChatPerformanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public long start() {
        return System.nanoTime();
    }

    public void record(String stage, long startedNanos) {
        record(stage, startedNanos, "success");
    }

    @Override
    public void record(String stage, long startedNanos, String result) {
        try {
            Timer.builder(STAGE_DURATION)
                    .description("Latency of each chat request pipeline stage")
                    .tag("stage", stage)
                    .tag("result", result)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofMinutes(5))
                    .register(meterRegistry)
                    .record(Math.max(0, System.nanoTime() - startedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // Metrics must never affect chat execution.
        }
    }

    public void fastPath(String reason) {
        try {
            Counter.builder(FAST_PATH_REQUESTS)
                    .description("Chat requests that bypass external search")
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Metrics must never affect chat execution.
        }
    }

    public void generationProfile(String profile, int maxTokens) {
        try {
            Counter.builder(GENERATION_PROFILES)
                    .tag("profile", profile)
                    .register(meterRegistry)
                    .increment();
            DistributionSummary.builder(TOKENS)
                    .description("Configured and observed token counts for chat generation")
                    .baseUnit("tokens")
                    .tag("type", "maximum")
                    .tag("profile", profile)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(maxTokens);
        } catch (RuntimeException ignored) {
            // Metrics must never affect generation profile selection.
        }
    }

    public void modelUsage(int promptTokens, int completionTokens, long durationNanos) {
        try {
            recordTokens("prompt", promptTokens);
            recordTokens("completion", completionTokens);
            if (completionTokens > 0 && durationNanos > 0) {
                double seconds = durationNanos / 1_000_000_000.0;
                DistributionSummary.builder(TOKENS_PER_SECOND)
                        .description("Observed model completion throughput")
                        .baseUnit("tokens_per_second")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
                        .record(completionTokens / seconds);
            }
        } catch (RuntimeException ignored) {
            // Usage metadata is optional and must never affect a response.
        }
    }

    private void recordTokens(String type, int tokens) {
        if (tokens < 1) {
            return;
        }
        DistributionSummary.builder(TOKENS)
                .description("Configured and observed token counts for chat generation")
                .baseUnit("tokens")
                .tag("type", type)
                .tag("profile", "observed")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(tokens);
    }
}
