package com.minikun.relationship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ConversationThreadServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T03:00:00Z");
    private final ConversationThreadService service = new ConversationThreadService(
            new InMemoryConversationThreadStore(), Clock.fixed(NOW, ZoneId.of("Asia/Bangkok")),
            ZoneId.of("Asia/Bangkok"));

    @Test
    void capturesExplicitlyUnresolvedConversation() {
        ConversationThread thread = service.observeTurn("owner", "conversation",
                "เรื่องย้ายงานเรายังตัดสินใจไม่ได้ ขอคิดก่อน", "ลองพักการตัดสินใจไว้ก่อนครับ")
                .orElseThrow();

        assertEquals(ConversationThreadStatus.OPEN, thread.status());
        assertFalse(thread.checkInConsent());
        assertTrue(service.promptContext("owner", "another", "ย้ายงาน").contains("ย้ายงาน"));
    }

    @Test
    void schedulesOnlyWhenUserExplicitlyRequestsCheckIn() {
        ConversationThread thread = service.observeTurn("owner", "conversation",
                "ถามเรื่องออกกำลังกายอีกทีพรุ่งนี้ตอนเช้านะ", "ได้ครับ")
                .orElseThrow();

        assertTrue(thread.checkInConsent());
        assertEquals(Instant.parse("2026-08-29T02:00:00Z"), thread.checkInAt());
        assertEquals(1, service.due(thread.checkInAt()).size());
    }

    @Test
    void rejectsScheduledTimeWithoutConsent() {
        boolean rejected = false;
        try {
            service.create("owner", "conversation", "topic", "", "", "", NOW.plusSeconds(60), false);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }
}
