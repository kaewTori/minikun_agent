package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;

class ReminderContextToolRouterTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void usesPreviousScheduleWhenFollowUpOnlySaysSetReminder() {
        ConversationId conversation = new ConversationId("context-reminder");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of(new ChatMessage(
                "user", "แล้วก็วันที่ 21 ตอน 5 ทุ่มมี post trst AD om ของ service MyNetwork")));
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                "Asia/Bangkok");

        ToolEvidence result = router.route("ตั้งการแจ้งเตือนให้หน่อย", conversation).orElseThrow();

        assertTrue(result.success());
        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-21T23:00:00+07:00", captured.get("at"));
        assertEquals("post trst AD om ของ service MyNetwork", captured.get("title"));
    }

    @Test
    void classifiesDayOfMonthReminderInOneMessage() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                "Asia/Bangkok");

        ToolEvidence result = router.route(
                "วันที่ 21 ตอน 5 ทุ่มตั้งเตือน post trst AD om ของ service MyNetwork",
                new ConversationId("one-message")).orElseThrow();

        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-21T23:00:00+07:00", captured.get("at"));
    }

    @Test
    void usesThaiEarlyMorningTimeFromPreviousContext() {
        ConversationId conversation = new ConversationId("context-ตี-five");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of(new ChatMessage(
                "user", "แล้วก็วันที่ 21 ตี 5 มี post trst AD om ของ service MyNetwork")));
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
                "Asia/Bangkok");

        ToolEvidence result = router.route("ตั้งการแจ้งเตือนให้หน่อย", conversation).orElseThrow();

        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-21T05:00:00+07:00", captured.get("at"));
        assertEquals("post trst AD om ของ service MyNetwork", captured.get("title"));
    }

    @Test
    void combinesPreviousDateContextWithCurrentReminderTime() {
        ConversationId conversation = new ConversationId("date-before-reminder");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of(
                new ChatMessage("user", "พรุ่งนี้มีอะไรที่เราต้องทำบ้าง"),
                new ChatMessage("assistant", "ยังไม่มีกำหนดการครับ")));
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, CLOCK, "Asia/Bangkok");

        ToolEvidence result = router.route(
                "ช่วยเตือนเราตอน 9 โมงให้หน่อยว่ามีนัดตอน 11 โมงไปงาน Lily festival ที่ JJ mall",
                conversation).orElseThrow();

        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-20T09:00:00+07:00", captured.get("at"));
        assertEquals("มีนัดตอน 11 โมงไปงาน Lily festival ที่ JJ mall", captured.get("title"));
    }

    @Test
    void asksForMissingDateInsteadOfFallingThroughToTheModel() {
        ConversationId conversation = new ConversationId("missing-date");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of());
        AtomicBoolean executed = new AtomicBoolean();
        ToolExecutor executor = (context, call) -> {
            executed.set(true);
            return ToolResult.success(Map.of());
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, CLOCK, "Asia/Bangkok");

        ToolEvidence result = router.route(
                "ช่วยเตือนตอน 9 โมงว่ามีนัดตอน 11 โมงไปงาน Lily festival ที่ JJ mall",
                conversation).orElseThrow();

        assertTrue(result.finalResponse());
        assertTrue(result.content().contains("วันที่ไหน"));
        assertTrue(result.content().contains("09:00"));
        assertFalse(executed.get());
    }

    @Test
    void resumesIncompleteReminderWhenDateArrivesInFollowUp() {
        ConversationId conversation = new ConversationId("date-follow-up");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of(
                new ChatMessage("user",
                        "ช่วยเตือนตอน 9 โมงว่ามีนัดตอน 11 โมงไปงาน Lily festival ที่ JJ mall"),
                new ChatMessage("assistant", "ต้องการให้เตือนวันที่ไหน เวลา 09:00 น. ครับ")));
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, CLOCK, "Asia/Bangkok");

        ToolEvidence result = router.route("พรุ่งนี้", conversation).orElseThrow();

        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-20T09:00:00+07:00", captured.get("at"));
        assertEquals("มีนัดตอน 11 โมงไปงาน Lily festival ที่ JJ mall", captured.get("title"));
    }

    @Test
    void asksOnlyForTimeWhenReminderAlreadyHasADate() {
        ConversationId conversation = new ConversationId("missing-time");
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(conversation)).thenReturn(List.of());
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                (context, call) -> ToolResult.success(Map.of()), memory, CLOCK, "Asia/Bangkok");

        ToolEvidence result = router.route("พรุ่งนี้ช่วยเตือนเรื่องส่งรายงาน", conversation).orElseThrow();

        assertTrue(result.finalResponse());
        assertTrue(result.content().contains("เวลาเท่าไร"));
        assertFalse(result.content().contains("วันไหนและ"));
    }

    @Test
    void supportsDayAfterTomorrowAndExplicitIsoDate() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        ReminderContextToolRouter router = new ReminderContextToolRouter(
                executor, memory, CLOCK, "Asia/Bangkok");

        assertTrue(router.route("มะรืนตอน 9 โมงเตือนส่งรายงาน", new ConversationId("day-after"))
                .orElseThrow().requiresConfirmation());
        assertEquals("2026-08-21T09:00:00+07:00", captured.get("at"));

        captured.clear();
        assertTrue(router.route("2026-08-25 เวลา 14:30 เตือนประชุม", new ConversationId("iso-date"))
                .orElseThrow().requiresConfirmation());
        assertEquals("2026-08-25T14:30:00+07:00", captured.get("at"));
    }
}
