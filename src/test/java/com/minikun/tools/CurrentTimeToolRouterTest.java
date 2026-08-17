package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

class CurrentTimeToolRouterTest {
    @Test
    void routesUnambiguousTimeQuestionToNativeTool() {
        CurrentTimeTool tool = new CurrentTimeTool(
                Clock.fixed(Instant.parse("2026-08-18T01:38:57Z"), ZoneOffset.UTC), "Asia/Bangkok");
        CurrentTimeToolRouter router = new CurrentTimeToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(List.of(tool))));

        ToolEvidence result = router.route("ตอนนี้กี่โมงครับ", new ConversationId("time")).orElseThrow();

        assertTrue(result.success());
        assertEquals("time.get_current_time", result.toolName());
        assertTrue(result.content().contains("เขตเวลา: Asia/Bangkok"));
        assertTrue(result.content().contains("เวลา: 08:38:57"));
    }

    @Test
    void leavesExplicitTimezoneForModelToolCalling() {
        CurrentTimeToolRouter router = new CurrentTimeToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(List.of(new CurrentTimeTool(Clock.systemUTC(), "Asia/Bangkok")))));

        assertEquals(java.util.Optional.empty(), router.route(
                "ตอนนี้กี่โมงที่ London", new ConversationId("time")));
    }
}
