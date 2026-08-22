package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

class TaskModelConversationSummaryGeneratorTest {
    @Test
    void sendsExistingSummaryAndNewEvidenceToTaskModel() {
        AtomicReference<TaskModelRequest> captured = new AtomicReference<>();
        TaskModelProvider provider = request -> {
            captured.set(request);
            return "<think>hidden</think>\n- พี่เลือกปลูกมะลิ";
        };
        TaskModelConversationSummaryGenerator generator =
                new TaskModelConversationSummaryGenerator(provider, 384);

        String result = generator.update("- กำลังจัดสวน", List.of(
                new ChatMessage("user", "เลือกดอกมะลิ"),
                new ChatMessage("assistant", "รับทราบครับ")));

        assertEquals("- พี่เลือกปลูกมะลิ", result);
        assertEquals(384, captured.get().maxOutputTokens());
        assertEquals(0.0, captured.get().temperature());
        String prompt = captured.get().messages().getFirst().content();
        assertTrue(prompt.startsWith("/no_think\n"));
        assertTrue(prompt.contains("- กำลังจัดสวน"));
        assertTrue(prompt.contains("user: เลือกดอกมะลิ"));
        assertTrue(prompt.contains("Never invent facts"));
    }
}
