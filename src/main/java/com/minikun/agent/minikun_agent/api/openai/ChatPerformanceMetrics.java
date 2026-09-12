package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
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
    static final String PROMPT_TOKEN_ESTIMATE_RATIO = "minikun.chat.prompt.token.estimate.ratio";
    static final String FINISH_REASONS = "minikun.chat.generation.finish.requests";
    static final String TRUNCATED = "minikun.chat.generation.truncated";
    static final String CONTINUATIONS = "minikun.chat.generation.continuation.requests";
    static final String TURN_PLANS = "minikun.chat.turn.plan.requests";

    private final MeterRegistry meterRegistry;
    private final AtomicInteger backgroundQueueDepth = new AtomicInteger();
    private final AtomicInteger backgroundActive = new AtomicInteger();
    private final AtomicInteger imageQueueDepth = new AtomicInteger();
    private final AtomicInteger imageActive = new AtomicInteger();

    public ChatPerformanceMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauge("minikun.chat.background.queue.depth", backgroundQueueDepth,
                "Number of background chat jobs waiting for a worker");
        registerGauge("minikun.chat.background.active", backgroundActive,
                "Number of background chat jobs actively using the chat pipeline");
        registerGauge("minikun.chat.image.queue.depth", imageQueueDepth,
                "Number of illustration jobs waiting for TinyGrad");
        registerGauge("minikun.chat.image.active", imageActive,
                "Number of illustration jobs actively using TinyGrad");
    }

    public void backgroundQueue(int queued, int active) {
        backgroundQueueDepth.set(Math.max(0, queued));
        backgroundActive.set(Math.max(0, active));
    }

    public void backgroundOutcome(String result) {
        try {
            Counter.builder("minikun.chat.background.requests")
                    .tag("result", safeTag(result))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Metrics must never affect background completion.
        }
    }

    public void imageQueue(int queued, int active) {
        imageQueueDepth.set(Math.max(0, queued));
        imageActive.set(Math.max(0, active));
    }

    public void imageOutcome(String result) {
        try {
            Counter.builder("minikun.chat.image.requests")
                    .tag("result", safeTag(result))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Metrics must never affect image completion.
        }
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

    public void effectiveGenerationLimit(String profile, Integer maxTokens) {
        if (maxTokens == null || maxTokens < 1) {
            return;
        }
        try {
            DistributionSummary.builder(TOKENS)
                    .description("Configured and observed token counts for chat generation")
                    .baseUnit("tokens")
                    .tag("type", "effective_maximum")
                    .tag("profile", safeTag(profile))
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(maxTokens);
        } catch (RuntimeException ignored) {
            // Metrics must never affect prompt construction.
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

    public void promptTokenEstimate(long estimatedTokens, int actualTokens) {
        if (estimatedTokens < 0 || actualTokens < 1) {
            return;
        }
        try {
            DistributionSummary.builder(PROMPT_TOKEN_ESTIMATE_RATIO)
                    .description("Estimated prompt tokens divided by model-reported prompt tokens")
                    .baseUnit("ratio")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record((double) estimatedTokens / actualTokens);
        } catch (RuntimeException ignored) {
            // Token estimation diagnostics must never affect a response.
        }
    }

    public void continuation(String profile, String result) {
        try {
            Counter.builder(CONTINUATIONS)
                    .description("Length-limited responses that entered automatic continuation")
                    .tag("profile", safeTag(profile))
                    .tag("result", safeTag(result))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Metrics must never affect continuation.
        }
    }

    public void generationOutcome(String profile, String finishReason, int continuationCount) {
        try {
            String safeProfile = safeTag(profile);
            String safeReason = safeTag(finishReason);
            Counter.builder(FINISH_REASONS)
                    .description("Final model finish reasons after bounded recovery")
                    .tag("profile", safeProfile)
                    .tag("reason", safeReason)
                    .tag("continued", continuationCount > 0 ? "true" : "false")
                    .register(meterRegistry)
                    .increment();
            if ("length".equals(safeReason)) {
                Counter.builder(TRUNCATED)
                        .description("Responses still truncated after bounded recovery")
                        .tag("profile", safeProfile)
                        .register(meterRegistry)
                        .increment();
            }
        } catch (RuntimeException ignored) {
            // Metrics must never affect response delivery.
        }
    }

    public void turnPlan(TurnPlan plan) {
        if (plan == null) return;
        try {
            Counter.builder(TURN_PLANS)
                    .description("Resolved chat turn routes")
                    .tag("intent", plan.intentTag())
                    .tag("execution", plan.executionTag())
                    .tag("ambiguous", Boolean.toString(plan.ambiguous()))
                    .tag("technical", Boolean.toString(plan.cooperation().needsExpert()))
                    .register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Planning observability must never affect a response.
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

    private void registerGauge(String name, AtomicInteger value, String description) {
        try {
            Gauge.builder(name, value, AtomicInteger::get)
                    .description(description)
                    .register(meterRegistry);
        } catch (RuntimeException ignored) {
            // A shared registry may already own this gauge; metrics stay best-effort.
        }
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
