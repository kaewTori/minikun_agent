package com.minikun.agent.minikun_agent.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Maintains a persistent rolling summary outside the latency-sensitive chat request path. */
@Slf4j
public final class ConversationSummaryService implements AutoCloseable {
    private static final int LOCK_STRIPES = 32;
    private final ConversationSummaryStore store;
    private final ConversationSummaryGenerator generator;
    private final Clock clock;
    private final boolean enabled;
    private final int minimumHistoryMessages;
    private final int retainedRecentMessages;
    private final int maximumSummaryCharacters;
    private final int maximumTrackedFingerprints;
    private final ThreadPoolExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Set<Scope> pending = ConcurrentHashMap.newKeySet();
    private final Set<Scope> suppressed = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Scope, Supplier<List<ChatMessage>>> latestHistories = new ConcurrentHashMap<>();
    private final Object[] scopeLocks = locks();

    public ConversationSummaryService(
            ConversationSummaryStore store,
            ConversationSummaryGenerator generator,
            Clock clock,
            boolean enabled,
            int minimumHistoryMessages,
            int retainedRecentMessages,
            int maximumSummaryCharacters,
            int maximumTrackedFingerprints,
            int queueCapacity) {
        this(store, generator, clock, enabled, minimumHistoryMessages, retainedRecentMessages,
                maximumSummaryCharacters, maximumTrackedFingerprints, queueCapacity, null);
    }

    public ConversationSummaryService(
            ConversationSummaryStore store,
            ConversationSummaryGenerator generator,
            Clock clock,
            boolean enabled,
            int minimumHistoryMessages,
            int retainedRecentMessages,
            int maximumSummaryCharacters,
            int maximumTrackedFingerprints,
            int queueCapacity,
            MeterRegistry meterRegistry) {
        this.store = Objects.requireNonNull(store, "summary store must not be null");
        this.generator = Objects.requireNonNull(generator, "summary generator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (minimumHistoryMessages <= retainedRecentMessages) {
            throw new IllegalArgumentException("minimum history messages must exceed retained recent messages");
        }
        if (retainedRecentMessages < 2) {
            throw new IllegalArgumentException("retained recent messages must be at least two");
        }
        if (maximumSummaryCharacters <= 0 || maximumTrackedFingerprints <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("summary limits and queue capacity must be positive");
        }
        this.enabled = enabled;
        this.minimumHistoryMessages = minimumHistoryMessages;
        this.retainedRecentMessages = retainedRecentMessages;
        this.maximumSummaryCharacters = maximumSummaryCharacters;
        this.maximumTrackedFingerprints = maximumTrackedFingerprints;
        this.meterRegistry = meterRegistry;
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "minikun-conversation-summary");
                    thread.setDaemon(true);
                    return thread;
                });
        if (meterRegistry != null) {
            Gauge.builder("minikun.conversation.summary.queue.depth", executor,
                            worker -> worker.getQueue().size())
                    .register(meterRegistry);
        }
    }

    public Optional<String> summary(String ownerId, ConversationId conversationId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return store.find(required(ownerId, "owner id"),
                            Objects.requireNonNull(conversationId, "conversation id must not be null"))
                    .map(ConversationSummary::content)
                    .filter(content -> !content.isBlank());
        } catch (RuntimeException exception) {
            log.warn("process=conversation_summary event=load_failed conversation_id={}",
                    conversationId == null ? "-" : conversationId.value(), exception);
            return Optional.empty();
        }
    }

    public int recentMessageLimit() {
        return retainedRecentMessages;
    }

    /** Coalesces repeated completion events for one conversation and loads history only in the worker. */
    public boolean schedule(
            String ownerId,
            ConversationId conversationId,
            Supplier<List<ChatMessage>> historySupplier) {
        if (!enabled) {
            return false;
        }
        Scope scope = new Scope(required(ownerId, "owner id"),
                Objects.requireNonNull(conversationId, "conversation id must not be null"));
        Objects.requireNonNull(historySupplier, "history supplier must not be null");
        suppressed.remove(scope);
        latestHistories.put(scope, historySupplier);
        if (pending.contains(scope)) {
            increment("coalesced");
        }
        return submitIfNeeded(scope);
    }

    private boolean submitIfNeeded(Scope scope) {
        if (!pending.add(scope)) {
            return true;
        }
        try {
            executor.execute(() -> process(scope));
            return true;
        } catch (RejectedExecutionException exception) {
            pending.remove(scope);
            latestHistories.remove(scope);
            increment("queue_full");
            log.warn("process=conversation_summary event=queue_full conversation_id={}",
                    scope.conversationId().value());
            return false;
        }
    }

    private void process(Scope scope) {
        try {
            Supplier<List<ChatMessage>> historySupplier;
            while ((historySupplier = latestHistories.remove(scope)) != null) {
                Timer.Sample sample = meterRegistry == null ? null : Timer.start(meterRegistry);
                try {
                    boolean updated = updateNow(
                            scope.ownerId(), scope.conversationId(), historySupplier.get());
                    increment(updated ? "updated" : "noop");
                } catch (RuntimeException exception) {
                    increment("failed");
                    log.warn("process=conversation_summary event=update_failed conversation_id={}",
                            scope.conversationId().value(), exception);
                } finally {
                    if (sample != null) {
                        sample.stop(Timer.builder("minikun.conversation.summary.duration")
                                .register(meterRegistry));
                    }
                }
            }
        } finally {
            pending.remove(scope);
            if (latestHistories.containsKey(scope)) {
                submitIfNeeded(scope);
            } else {
                suppressed.remove(scope);
            }
        }
    }

    /** Synchronous boundary used by the worker and deterministic unit tests. */
    public boolean updateNow(String ownerId, ConversationId conversationId, List<ChatMessage> history) {
        if (!enabled) {
            return false;
        }
        String owner = required(ownerId, "owner id");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        List<ChatMessage> usableHistory = usable(history);
        if (usableHistory.size() < minimumHistoryMessages) {
            return false;
        }

        Scope scope = new Scope(owner, conversationId);
        synchronized (lockFor(scope)) {
            if (suppressed.contains(scope)) {
                return false;
            }
            ConversationSummary current = store.find(owner, conversationId).orElse(null);
            return summarize(owner, conversationId, usableHistory, current);
        }
    }

    public boolean rebuildNow(String ownerId, ConversationId conversationId, List<ChatMessage> history) {
        if (!enabled) {
            return false;
        }
        String owner = required(ownerId, "owner id");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        List<ChatMessage> usableHistory = usable(history);
        if (usableHistory.size() < minimumHistoryMessages) {
            return false;
        }
        Scope scope = new Scope(owner, conversationId);
        suppressed.remove(scope);
        synchronized (lockFor(scope)) {
            return summarize(owner, conversationId, usableHistory, null);
        }
    }

    public boolean clear(String ownerId, ConversationId conversationId) {
        String owner = required(ownerId, "owner id");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Scope scope = new Scope(owner, conversationId);
        suppressed.add(scope);
        latestHistories.remove(scope);
        synchronized (lockFor(scope)) {
            boolean deleted = store.delete(owner, conversationId);
            if (!pending.contains(scope)) suppressed.remove(scope);
            return deleted;
        }
    }

    public ConversationSummaryStatus status(
            String ownerId,
            ConversationId conversationId,
            int historyMessages) {
        String owner = required(ownerId, "owner id");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        ConversationSummary summary = store.find(owner, conversationId).orElse(null);
        long age = summary == null ? 0 : Math.max(0,
                Duration.between(summary.updatedAt(), clock.instant()).toSeconds());
        return new ConversationSummaryStatus(
                owner, conversationId.value(), enabled, summary != null,
                summary == null ? "" : summary.content(),
                summary == null ? 0 : summary.summarizedMessages(),
                summary == null ? null : summary.updatedAt(), age,
                Math.max(0, historyMessages), pending.contains(new Scope(owner, conversationId)));
    }

    private boolean summarize(
            String owner,
            ConversationId conversationId,
            List<ChatMessage> usableHistory,
            ConversationSummary current) {
        int olderEnd = usableHistory.size() - retainedRecentMessages;
        List<ChatMessage> olderMessages = usableHistory.subList(0, olderEnd);
        LinkedHashSet<String> covered = new LinkedHashSet<>(
                current == null ? List.of() : current.coveredFingerprints());
        List<ChatMessage> unseen = new ArrayList<>();
        List<String> unseenFingerprints = new ArrayList<>();
        for (ChatMessage message : olderMessages) {
            String fingerprint = fingerprint(message);
            if (!covered.contains(fingerprint)) {
                unseen.add(message);
                unseenFingerprints.add(fingerprint);
            }
        }
        if (unseen.isEmpty()) {
            return false;
        }

        String generated = generator.update(current == null ? "" : current.content(), List.copyOf(unseen));
        String content = bounded(generated);
        if (content.isBlank()) {
            throw new IllegalStateException("conversation summary generator returned blank content");
        }
        unseenFingerprints.forEach(covered::add);
        List<String> retainedFingerprints = retainNewest(covered);
        int summarizedMessages = (current == null ? 0 : current.summarizedMessages()) + unseen.size();
        store.save(new ConversationSummary(owner, conversationId, content, retainedFingerprints,
                summarizedMessages, clock.instant()));
        log.info("process=conversation_summary event=updated conversation_id={} new_messages={} "
                        + "total_summarized_messages={} summary_chars={}",
                conversationId.value(), unseen.size(), summarizedMessages, content.length());
        return true;
    }

    private void increment(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("minikun.conversation.summary.jobs")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private Object lockFor(Scope scope) {
        return scopeLocks[Math.floorMod(scope.hashCode(), scopeLocks.length)];
    }

    private static Object[] locks() {
        Object[] values = new Object[LOCK_STRIPES];
        java.util.Arrays.setAll(values, ignored -> new Object());
        return values;
    }

    private List<ChatMessage> usable(List<ChatMessage> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream()
                .filter(Objects::nonNull)
                .filter(message -> "user".equalsIgnoreCase(message.role())
                        || "assistant".equalsIgnoreCase(message.role()))
                .toList();
    }

    private String bounded(String generated) {
        String normalized = Objects.requireNonNullElse(generated, "").strip();
        return normalized.length() <= maximumSummaryCharacters
                ? normalized
                : normalized.substring(0, maximumSummaryCharacters).stripTrailing();
    }

    private List<String> retainNewest(LinkedHashSet<String> fingerprints) {
        List<String> values = new ArrayList<>(fingerprints);
        int from = Math.max(0, values.size() - maximumTrackedFingerprints);
        return List.copyOf(values.subList(from, values.size()));
    }

    private String fingerprint(ChatMessage message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = (message.role().toLowerCase(java.util.Locale.ROOT) + "\n" + message.content())
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String required(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record Scope(String ownerId, ConversationId conversationId) {
    }
}
