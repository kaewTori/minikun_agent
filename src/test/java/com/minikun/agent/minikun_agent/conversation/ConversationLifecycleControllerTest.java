package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ConversationLifecycleControllerTest {

    @Test
    void clearsShortTermMessagesAndRollingSummaryTogether() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        ConversationSummaryService summary = mock(ConversationSummaryService.class);
        when(summary.clear("owner", new ConversationId("chat-1"))).thenReturn(true);
        ConversationLifecycleController controller = new ConversationLifecycleController(memory, summary);

        Map<String, Object> result = controller.clear("chat-1", "owner", null);

        verify(memory).clear(new ConversationId("chat-1"));
        verify(summary).clear("owner", new ConversationId("chat-1"));
        assertEquals(true, result.get("deleted"));
        assertEquals(true, result.get("summary_deleted"));
    }
}
