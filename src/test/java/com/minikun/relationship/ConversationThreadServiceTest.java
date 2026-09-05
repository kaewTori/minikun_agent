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
    void keepsExplicitWorkingDecisionAcrossConversations() {
        ConversationThread thread = service.observeTurn("owner", "conversation",
                "จำไว้ว่า สำหรับโปรเจกต์มินิคุงเราตกลงใช้ PostgreSQL", "รับทราบครับ")
                .orElseThrow();

        assertTrue(thread.lastDecision().contains("PostgreSQL"));
        assertTrue(service.promptContext("owner", "another", "โปรเจกต์มินิคุง")
                .contains("PostgreSQL"));
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

    @Test
    void keepsRecentEmotionalContextWithoutPersistingTheRawMessage() {
        service.observeTurn("owner", "conversation", "วันนี้เหนื่อยและเครียดมาก", "พักก่อนก็ได้ครับ");

        String context = service.promptContext("owner", "another", "คุยกันหน่อย");

        assertTrue(context.contains("Recent emotional context: SUPPORTIVE"));
        assertFalse(context.contains("วันนี้เหนื่อยและเครียดมาก"));
    }

    @Test
    void clearsEmotionalContextWhenUserExplicitlyFeelsBetter() {
        service.observeTurn("owner", "conversation", "วันนี้เหนื่อยและเครียดมาก", "พักก่อนก็ได้ครับ");
        service.observeTurn("owner", "conversation", "ตอนนี้ดีขึ้นแล้วนะ", "ดีใจด้วยครับ");

        assertTrue(service.promptContext("owner", "another", "คุยกันหน่อย").isBlank());
    }

    @Test
    void careFeedbackCanSnoozeOrStopAConsentedCheckIn() {
        ConversationThread thread = service.observeTurn("owner", "conversation",
                "ถามเรื่องออกกำลังกายอีกทีพรุ่งนี้ตอนเช้านะ", "ได้ครับ").orElseThrow();

        ConversationThread snoozed = service.feedback("owner", thread.id(),
                ConversationCheckInFeedback.NOT_NOW, NOW.plusSeconds(7200));
        assertEquals(ConversationCheckInFeedback.NOT_NOW, snoozed.lastCheckInFeedback());
        assertTrue(snoozed.checkInConsent());

        ConversationThread stopped = service.feedback("owner", thread.id(),
                ConversationCheckInFeedback.STOP_THIS_TOPIC, null);
        assertEquals(ConversationThreadStatus.RESOLVED, stopped.status());
        assertFalse(stopped.checkInConsent());
    }
}
