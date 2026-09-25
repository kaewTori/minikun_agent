package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConversationSummaryServiceTest {
    private static final ConversationId CONVERSATION = new ConversationId("conversation-1");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void incrementallySummarizesOnlyNewlyAgedOutMessages() {
        InMemoryStore store = new InMemoryStore();
        List<List<ChatMessage>> batches = new ArrayList<>();
        ConversationSummaryGenerator generator = (existing, messages) -> {
            batches.add(List.copyOf(messages));
            String addition = messages.stream().map(ChatMessage::content)
                    .reduce((left, right) -> left + "," + right).orElse("");
            return existing.isBlank() ? addition : existing + "\n" + addition;
        };

        try (ConversationSummaryService service = service(store, generator, 1_800)) {
            assertTrue(service.updateNow("owner", CONVERSATION, history(5)));
            assertTrue(service.updateNow("owner", CONVERSATION, history(6)));
            assertFalse(service.updateNow("owner", CONVERSATION, history(6)));
        }

        assertEquals(List.of(2, 2), batches.stream().map(List::size).toList());
        assertEquals(List.of("user-0", "assistant-0"),
                batches.getFirst().stream().map(ChatMessage::content).toList());
        assertEquals(List.of("user-1", "assistant-1"),
                batches.get(1).stream().map(ChatMessage::content).toList());
        ConversationSummary summary = store.find("owner", CONVERSATION).orElseThrow();
        assertEquals(4, summary.summarizedMessages());
        assertEquals(4, summary.coveredFingerprints().size());
        assertTrue(summary.content().contains("assistant-1"));
        assertEquals(CLOCK.instant(), summary.updatedAt());
    }

    @Test
    void boundsGeneratedSummaryAndLoadsItForPromptUse() {
        InMemoryStore store = new InMemoryStore();
        try (ConversationSummaryService service = service(store,
                (existing, messages) -> "ส".repeat(500), 120)) {
            assertTrue(service.updateNow("owner", CONVERSATION, history(5)));
            assertEquals(120, service.summary("owner", CONVERSATION).orElseThrow().length());
            assertEquals(8, service.recentMessageLimit(
                    service.snapshot("owner", CONVERSATION).orElseThrow(), history(5)));
        }
    }

    @Test
    void keepsTurnsThatAnAsyncSummaryHasNotCoveredYet() {
        InMemoryStore store = new InMemoryStore();
        try (ConversationSummaryService service = service(store,
                (existing, messages) -> "summary", 1_800)) {
            assertTrue(service.updateNow("owner", CONVERSATION, history(5)));
            ConversationSummary stale = service.snapshot("owner", CONVERSATION).orElseThrow();
            assertEquals(10, service.recentMessageLimit(stale, history(6)));

            assertTrue(service.updateNow("owner", CONVERSATION, history(6)));
            assertEquals(8, service.recentMessageLimit(
                    service.snapshot("owner", CONVERSATION).orElseThrow(), history(6)));
        }
    }

    @Test
    void workerFailureDoesNotEscapeChatCompletionPathOrOverwriteSummary() {
        InMemoryStore store = new InMemoryStore();
        ConversationSummary existing = new ConversationSummary(
                "owner", CONVERSATION, "existing", List.of(), 0, CLOCK.instant());
        store.save(existing);

        try (ConversationSummaryService service = service(store,
                (summary, messages) -> { throw new IllegalStateException("model unavailable"); }, 1_800)) {
            assertTrue(service.schedule("owner", CONVERSATION, () -> history(5)));
        }

        assertEquals(existing, store.find("owner", CONVERSATION).orElseThrow());
    }

    @Test
    void coalescedCompletionUsesTheNewestHistoryWithoutWaitingForAnotherTurn() throws Exception {
        InMemoryStore store = new InMemoryStore();
        CountDownLatch firstGenerationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstGeneration = new CountDownLatch(1);
        AtomicInteger generations = new AtomicInteger();
        ConversationSummaryGenerator generator = (existing, messages) -> {
            if (generations.incrementAndGet() == 1) {
                firstGenerationStarted.countDown();
                try {
                    assertTrue(releaseFirstGeneration.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return existing + messages.stream().map(ChatMessage::content)
                    .reduce("", (left, right) -> left + "\n" + right);
        };

        try (ConversationSummaryService service = service(store, generator, 1_800)) {
            assertTrue(service.schedule("owner", CONVERSATION, () -> history(5)));
            assertTrue(firstGenerationStarted.await(1, TimeUnit.SECONDS));
            assertTrue(service.schedule("owner", CONVERSATION, () -> history(6)));
            releaseFirstGeneration.countDown();
        }

        ConversationSummary summary = store.find("owner", CONVERSATION).orElseThrow();
        assertEquals(4, summary.summarizedMessages());
        assertEquals(2, generations.get());
    }

    @Test
    void validatesWindowConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationSummaryService(
                new InMemoryStore(), (existing, messages) -> "summary", CLOCK,
                true, 8, 8, 100, 10, 2));
    }

    @Test
    void statusClearAndRebuildAreOwnerScoped() {
        InMemoryStore store = new InMemoryStore();
        try (ConversationSummaryService service = service(store,
                (existing, messages) -> "rebuilt", 1_800)) {
            assertTrue(service.updateNow("owner", CONVERSATION, history(5)));

            ConversationSummaryStatus status = service.status("owner", CONVERSATION, 10);
            assertTrue(status.present());
            assertEquals("rebuilt", status.content());
            assertEquals(10, status.historyMessages());
            assertEquals(0, status.ageSeconds());

            assertTrue(service.rebuildNow("owner", CONVERSATION, history(6)));
            assertEquals(4, service.status("owner", CONVERSATION, 12).summarizedMessages());
            assertTrue(service.clear("owner", CONVERSATION));
            assertFalse(service.status("owner", CONVERSATION, 12).present());
        }
    }

    @Test
    void publishesBackgroundJobAndDurationMetrics() throws Exception {
        InMemoryStore store = new InMemoryStore();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (ConversationSummaryService service = new ConversationSummaryService(
                store, (existing, messages) -> "summary", CLOCK,
                true, 10, 8, 1_800, 128, 4, metrics)) {
            assertTrue(service.schedule("owner", CONVERSATION, () -> history(5)));
        }

        assertEquals(1.0, metrics.get("minikun.conversation.summary.jobs")
                .tag("outcome", "updated").counter().count());
        assertEquals(1, metrics.get("minikun.conversation.summary.duration").timer().count());
        assertEquals(0.0, metrics.get("minikun.conversation.summary.queue.depth").gauge().value());
    }

    private ConversationSummaryService service(
            ConversationSummaryStore store,
            ConversationSummaryGenerator generator,
            int maximumCharacters) {
        return new ConversationSummaryService(store, generator, CLOCK,
                true, 10, 8, maximumCharacters, 128, 4);
    }

    private List<ChatMessage> history(int turns) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int turn = 0; turn < turns; turn++) {
            messages.add(new ChatMessage("user", "user-" + turn));
            messages.add(new ChatMessage("assistant", "assistant-" + turn));
        }
        return List.copyOf(messages);
    }

    private static final class InMemoryStore implements ConversationSummaryStore {
        private final ConcurrentHashMap<String, ConversationSummary> summaries = new ConcurrentHashMap<>();

        @Override
        public Optional<ConversationSummary> find(String ownerId, ConversationId conversationId) {
            return Optional.ofNullable(summaries.get(ownerId + "\n" + conversationId.value()));
        }

        @Override
        public void save(ConversationSummary summary) {
            summaries.put(summary.ownerId() + "\n" + summary.conversationId().value(), summary);
        }

        @Override
        public boolean delete(String ownerId, ConversationId conversationId) {
            return summaries.remove(ownerId + "\n" + conversationId.value()) != null;
        }
    }
}
