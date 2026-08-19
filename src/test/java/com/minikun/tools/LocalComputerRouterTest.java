package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LocalComputerRouterTest {
    @Test
    void routesExplicitRootsRequestWithoutTheModel() {
        AtomicReference<ToolCall> captured = new AtomicReference<>();
        ToolExecutor executor = (context, call) -> {
            captured.set(call);
            return ToolResult.success(Map.of("roots", List.of(
                    Map.of("name", "documents", "available", true),
                    Map.of("name", "downloads", "available", true))));
        };
        LocalComputerRouter router = new LocalComputerRouter(executor, new ObjectMapper());

        Optional<ToolEvidence> evidence = router.route(
                "ใช้ computer.local โดยกำหนด action เป็น roots แล้วบอก root ที่เข้าถึงได้",
                new ConversationId("computer-router"), "owner");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().finalResponse());
        assertTrue(evidence.get().content().contains("documents"));
        assertEquals("roots", captured.get().arguments().get("action"));
        assertTrue(router.route("ช่วยจัดไฟล์ให้หน่อย", new ConversationId("unrelated")).isEmpty());
    }

    @Test
    void onlyReadsClipboardWhenTheRequestExplicitlySaysToReadIt() {
        ToolExecutor executor = (context, call) -> ToolResult.success(Map.of("clipboard", "safe", "redacted", true));
        LocalComputerRouter router = new LocalComputerRouter(executor, new ObjectMapper());

        assertTrue(router.route("อ่าน clipboard ให้หน่อย", new ConversationId("clipboard")).isPresent());
        assertFalse(router.route("เขียนข้อมูลนี้ลง clipboard", new ConversationId("clipboard-write")).isPresent());
    }
}
