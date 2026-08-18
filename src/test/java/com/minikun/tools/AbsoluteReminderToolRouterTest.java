package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

class AbsoluteReminderToolRouterTest {
    @Test
    void calculatesTomorrowNineOClockOnTheServer() {
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "proposed", call.arguments()));
        };
        AbsoluteReminderToolRouter router = new AbsoluteReminderToolRouter(
                executor, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC), "Asia/Bangkok");

        ToolEvidence result = router.route(
                "มินิคุง พรุ่งนี้ตอน 9 โมงช่วยเตือนเราว่าให้ตรวจสอบ service ที่มีการทำ db leak ให้เราหน่อยนะ",
                new ConversationId("absolute-reminder")).orElseThrow();

        assertTrue(result.success());
        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-20T09:00:00+07:00", captured.get("at"));
        assertEquals("ตรวจสอบ service ที่มีการทำ db leak", captured.get("title"));
        assertTrue(result.content().contains("ยังไม่ได้บันทึก"));
    }

    @Test
    void supportsTomorrowClockTimeAndLeavesAmbiguousRequestsForTheModel() {
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        AbsoluteReminderToolRouter router = new AbsoluteReminderToolRouter(
                executor, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC), "Asia/Bangkok");

        assertTrue(router.route("พรุ่งนี้เวลา 09:30 น. เตือนตรวจสอบระบบ", new ConversationId("clock"))
                .isPresent());
        assertEquals("2026-08-20T09:30:00+07:00", captured.get("at"));
        assertTrue(router.route("พรุ่งนี้ช่วยเตือนตรวจสอบระบบ", new ConversationId("missing-time")).isEmpty());
        assertTrue(router.route("วันนี้ตอน 9 โมงช่วยเตือนตรวจสอบระบบ", new ConversationId("today" )).isEmpty());
    }

    @Test
    void supportsThaiEarlyMorningTime() {
        Map<String, Object> captured = new LinkedHashMap<>();
        ToolExecutor executor = (context, call) -> {
            captured.putAll(call.arguments());
            return ToolResult.success(Map.of("requires_confirmation", true));
        };
        AbsoluteReminderToolRouter router = new AbsoluteReminderToolRouter(
                executor, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC), "Asia/Bangkok");

        ToolEvidence result = router.route(
                "พรุ่งนี้ตี 5 ช่วยเตือนตรวจสอบระบบ", new ConversationId("ตี-five")).orElseThrow();

        assertTrue(result.requiresConfirmation());
        assertEquals("2026-08-20T05:00:00+07:00", captured.get("at"));
        assertEquals("ตรวจสอบระบบ", captured.get("title"));
    }
}
