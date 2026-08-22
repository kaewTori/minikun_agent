package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class ConversationSummaryControllerTest {
    @Test
    void statusIsOwnerScopedAndProtectedByTheManagementToken() {
        ConversationSummaryService summaries = mock(ConversationSummaryService.class);
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ConversationId conversationId = new ConversationId("conversation-1");
        when(memory.load(conversationId)).thenReturn(List.of(
                new ChatMessage("user", "question"),
                new ChatMessage("assistant", "answer")));
        when(summaries.status("owner", conversationId, 2)).thenReturn(new ConversationSummaryStatus(
                "owner", conversationId.value(), true, true, "summary", 4,
                Instant.parse("2026-08-22T12:00:00Z"), 60, 2, false));
        ConversationSummaryController controller = new ConversationSummaryController(summaries, memory);
        ReflectionTestUtils.setField(controller, "managementToken", "secret");

        assertThrows(ResponseStatusException.class,
                () -> controller.status(conversationId.value(), "owner", "wrong"));
        ConversationSummaryStatus status = controller.status(
                conversationId.value(), "owner", "secret");

        assertEquals("summary", status.content());
        assertEquals(2, status.historyMessages());
    }
}
