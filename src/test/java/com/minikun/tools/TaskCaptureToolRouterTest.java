package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

class TaskCaptureToolRouterTest {
    private final ConversationId conversation = new ConversationId("task-capture-test");

    @Test
    void offersExplicitTaskStatementWithTheRequestOwner() {
        CapturingExecutor executor = new CapturingExecutor();
        TaskCaptureToolRouter router = new TaskCaptureToolRouter(executor);

        Optional<ToolEvidence> evidence = router.route("ฝากจำ ส่งใบเสนอราคาให้ลูกค้า", conversation, "owner-7");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().requiresConfirmation());
        assertEquals("owner-7", executor.context.ownerId());
        assertEquals("ส่งใบเสนอราคาให้ลูกค้า", executor.call.arguments().get("title"));
        assertEquals("TASK", executor.call.arguments().get("kind"));
    }

    @Test
    void doesNotConfuseReminderWithTaskCapture() {
        TaskCaptureToolRouter router = new TaskCaptureToolRouter(new CapturingExecutor());

        assertTrue(router.route("ช่วยเตือนส่งใบเสนอราคาในอีก 2 ชั่วโมง", conversation, "owner-7").isEmpty());
        assertTrue(router.route("ช่วยจำวิธีทำข้าวผัด", conversation, "owner-7").isPresent());
    }

    private static final class CapturingExecutor implements ToolExecutor {
        private ToolCallContext context;
        private ToolCall call;

        @Override
        public ToolResult execute(ToolCallContext context, ToolCall call) {
            this.context = context;
            this.call = call;
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "proposed", Map.of("title", call.arguments().get("title"))));
        }
    }
}
