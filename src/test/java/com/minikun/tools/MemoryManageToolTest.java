package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.memory.MemoryRepository;
import com.minikun.memory.management.MemoryManagementService;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryManageToolTest {
    @Test
    void remembersUsingTheOwnerFromToolContext() {
        CapturingRepository repository = new CapturingRepository();
        MemoryManageTool tool = new MemoryManageTool(new MemoryManagementService(repository));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("conversation"), "call", "owner-1"),
                Map.of("action", "remember", "category", "preference", "content", "ไม่กินเผ็ด"));

        assertTrue(result.success());
        assertEquals("owner-1", repository.saved.ownerId());
        assertEquals("conversation", repository.saved.conversationId());
        assertEquals("ไม่กินเผ็ด", repository.saved.content());
    }

    @Test
    void rejectsUnknownMemoryAction() {
        MemoryManageTool tool = new MemoryManageTool(new MemoryManagementService(new CapturingRepository()));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("conversation"), "call"),
                Map.of("action", "search"));

        assertTrue(!result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
    }

    private static final class CapturingRepository implements MemoryRepository {
        private AcceptedMemory saved;

        @Override
        public boolean save(AcceptedMemory memory) {
            saved = memory;
            return true;
        }

        @Override
        public List<Memory> findByOwner(String ownerId, int limit) {
            return List.of();
        }

        @Override
        public boolean deleteByOwner(String ownerId, MemoryId memoryId) {
            return false;
        }

        @Override
        public int deleteAllByOwner(String ownerId) {
            return 0;
        }
    }
}
