package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;

class ReminderContextToolRouterTest {
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
}
