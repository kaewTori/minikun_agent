package com.minikun.memory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

public class ReflectionService {
    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);
    private static final String REQUESTS = "minikun.memory.reflection.requests";
    private static final String DURATION = "minikun.memory.reflection.duration";
    private static final String FAILURES = "minikun.memory.reflection.failures";
    private static final String OUTCOMES = "minikun.memory.reflection.outcomes";

    private final ReflectionPromptBuilder promptBuilder;
    private final ReflectionClient client;
    private final ReflectionParser parser;
    private final ReflectionDecisionService decisionService;
    private final MemoryRepository repository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, ReflectionDecisionService decisionService,
            MemoryRepository repository, Clock clock) {
        this(promptBuilder, client, parser, decisionService, repository, clock, null);
    }

    public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, ReflectionDecisionService decisionService,
            MemoryRepository repository, Clock clock, MeterRegistry meterRegistry) {
        this.promptBuilder = promptBuilder;
        this.client = client;
        this.parser = parser;
        this.decisionService = decisionService;
        this.repository = repository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public void reflect(CompletedConversation conversation) {
        Timer.Sample sample = startTimer();
        long startedNanos = System.nanoTime();
        String outcome = "success";
        int acceptedCount = 0;
        int rejectedCount = 0;
        int persistedCount = 0;
        String previousConversationId = null;
        try {
            increment(REQUESTS);
            previousConversationId = putConversationId(conversation);
            ReflectionPrompt prompt = promptBuilder.build(conversation, java.time.LocalDate.now(clock));
            String response = client.reflect(prompt);
            List<com.minikun.memory.model.MemoryCandidate> candidates;
            try {
                candidates = parser.parse(response, conversation);
            } catch (RuntimeException exception) {
                outcome = "parser_failure";
                incrementFailure("parser");
                return;
            }
            List<AcceptedMemory> accepted;
            try {
                accepted = conversation.ownerId() == null
                    ? decisionService.decide(candidates)
                    : decisionService.decide(candidates, conversation.ownerId());
            } catch (RuntimeException exception) {
                outcome = "decision_failure";
                incrementFailure("decision");
                return;
            }
            acceptedCount = accepted.size();
            rejectedCount = Math.max(0, candidates.size() - acceptedCount);
            for (AcceptedMemory memory : accepted) {
                try {
                    if (repository.save(memory)) {
                        persistedCount++;
                    }
                } catch (RuntimeException exception) {
                    if (!"persistence_failure".equals(outcome)) {
                        outcome = "persistence_failure";
                        incrementFailure("repository");
                    }
                }
            }
        } catch (RuntimeException exception) {
            outcome = "failure";
            incrementFailure("unexpected");
            log.warn("memory_reflection failed before persistence", exception);
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            recordOutcome(outcome);
            stopTimer(sample, outcome);
            logExecution(conversation, outcome, acceptedCount, rejectedCount, persistedCount, duration);
            restoreConversationId(previousConversationId);
        }
    }

    private Timer.Sample startTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void stopTimer(Timer.Sample sample, String outcome) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(Timer.builder(DURATION)
                    .tag("result", outcome)
                    .register(meterRegistry));
        } catch (RuntimeException ignored) {
        }
    }

    private void increment(String name) {
        try {
            Counter.builder(name).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
        }
    }

    private void incrementFailure(String failureType) {
        try {
            Counter.builder(FAILURES)
                    .tag("failure_type", failureType)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
        }
    }

    private void recordOutcome(String outcome) {
        try {
            Counter.builder(OUTCOMES)
                    .tag("result", outcome)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
        }
    }

    private String putConversationId(CompletedConversation conversation) {
        try {
            String current = MDC.get("conversation_id");
            if (conversation != null && conversation.conversationId() != null) {
                MDC.put("conversation_id", conversation.conversationId());
            }
            return current;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void restoreConversationId(String previousConversationId) {
        try {
            if (previousConversationId == null) {
                MDC.remove("conversation_id");
            } else {
                MDC.put("conversation_id", previousConversationId);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void logExecution(CompletedConversation conversation, String outcome,
            int acceptedCount, int rejectedCount, int persistedCount, Duration duration) {
        try {
            log.info("memory_reflection outcome={} conversation_id={} accepted_count={} rejected_count={} persisted_count={} duration_ms={}",
                    outcome, conversation == null ? null : conversation.conversationId(),
                    acceptedCount, rejectedCount, persistedCount, duration.toMillis());
        } catch (RuntimeException ignored) {
        }
    }
}