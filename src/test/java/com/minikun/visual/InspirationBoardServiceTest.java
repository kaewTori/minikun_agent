package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InspirationBoardServiceTest {
    private final InspirationBoardService service = new InspirationBoardService(
            new InMemoryInspirationBoardStore(), Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC));

    @Test void createsBoardAndPersistsVisualReference() {
        InspirationBoard board = service.create("owner", "Moodboard");
        InspirationBoardItem item = service.add("owner", board.id(), "https://images.example/a.jpg",
                "https://source.example/page", "A", "description", "web");

        assertEquals("Moodboard", service.list("owner", 10).getFirst().title());
        assertEquals(item, service.items("owner", board.id(), 10).getFirst());
        assertTrue(service.remove("owner", board.id(), item.id()));
        assertTrue(service.items("owner", board.id(), 10).isEmpty());
    }

    @Test void isolatesOwnersAndRejectsNonWebUrls() {
        InspirationBoard board = service.create("owner-a", "References");
        assertThrows(IllegalArgumentException.class, () -> service.items("owner-b", board.id(), 10));
        assertThrows(IllegalArgumentException.class, () -> service.add("owner-a", board.id(),
                "file:///etc/passwd", "", "bad", "", "web"));
    }
}
