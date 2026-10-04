package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresentationStoreTest {
    @TempDir Path root;

    @Test
    void retriesReuseTheOwnerScopedArtifactAndOtherOwnersCannotDownloadIt() {
        PresentationStore store = new PresentationStore(root, 1024, Duration.ofDays(30),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC));
        PresentationSpec spec = new PresentationSpec("Deck", "en", "paper", List.of(
                new PresentationSpec.SlideSpec("One", "editorial", "", List.of(), "", List.of(), "", List.of(),
                        "", "", "", "", List.of(), "", "", List.of())));

        var first = store.save(new byte[] {1, 2, 3}, spec, "alice", "chat-1", "request-1");
        var retry = store.save(new byte[] {9, 9, 9}, spec, "alice", "chat-1", "request-1");

        assertEquals(first.artifactId(), retry.artifactId());
        assertArrayEquals(new byte[] {1, 2, 3}, store.bytes(retry));
        assertThrows(IllegalArgumentException.class, () -> store.read(first.artifactId(), "bob"));

        var differentSpec = new PresentationSpec("Different", "en", "paper", List.of(
                new PresentationSpec.SlideSpec("One", "editorial", "", List.of(), "", List.of(), "", List.of(),
                        "", "", "", "", List.of(), "", "", List.of())));
        var distinctOperation = store.save(new byte[] {4}, differentSpec, "alice", "chat-1", "request-1");
        assertNotEquals(first.artifactId(), distinctOperation.artifactId());
    }

    @Test
    void latestRevisionIsOwnerAndConversationScopedAndKeepsItsParent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-03T00:00:00Z"));
        PresentationStore store = new PresentationStore(root, 1024, Duration.ofDays(30),
                new ObjectMapper().findAndRegisterModules(), clock);
        PresentationSpec originalSpec = spec("Original");
        var original = store.save(new byte[] {1}, originalSpec, "alice", "chat-1", "create");
        clock.advance(Duration.ofSeconds(1));
        var revision = store.save(new byte[] {2}, spec("Revised"), "alice", "chat-1", "revise",
                original.artifactId());

        assertEquals(revision.artifactId(), store.latest("alice", "chat-1").artifactId());
        assertEquals(original.artifactId(), store.read(revision.artifactId(), "alice").parentArtifactId());
        assertArrayEquals(new byte[] {1}, store.bytes(store.read(original.artifactId(), "alice")));
        assertThrows(IllegalArgumentException.class, () -> store.latest("bob", "chat-1"));
        assertThrows(IllegalArgumentException.class, () -> store.latest("alice", "chat-2"));
    }

    private PresentationSpec spec(String title) {
        return new PresentationSpec(title, "en", "paper", List.of(
                new PresentationSpec.SlideSpec("One", "editorial", "", List.of(), "", List.of(), "", List.of(),
                        "", "", "", "", List.of(), "", "", List.of())));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
