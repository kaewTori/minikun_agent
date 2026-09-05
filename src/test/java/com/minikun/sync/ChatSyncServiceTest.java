package com.minikun.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ChatSyncServiceTest {
    private final InMemoryChats repository = new InMemoryChats();
    private final ChatSyncService service = new ChatSyncService(repository, new SyncEventBroker(),
            Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
    private final DevicePairingService.Session session = new DevicePairingService.Session(
            UUID.randomUUID(), "default", "Mac");

    @Test
    void savesAppendOnlyConversationForThePairedOwner() {
        ChatSyncConversation input = new ChatSyncConversation("chat-1", "ทดสอบ", null, List.of(
                new ChatSyncConversation.Message(null, "user", "สวัสดี", List.of(), List.of(),
                        null, null, Instant.parse("2026-08-26T00:00:00Z"))));

        ChatSyncConversation saved = service.save(session, "chat-1", input);

        assertEquals("default", repository.lastOwner);
        assertFalse(saved.messages().getFirst().id().isBlank());
        assertEquals(saved, service.list(session).getFirst());
    }

    @Test
    void rejectsMismatchedConversationIdAndUnsupportedRole() {
        ChatSyncConversation mismatch = new ChatSyncConversation("other", "หัวข้อ", Instant.now(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.save(session, "path", mismatch));

        ChatSyncConversation tool = new ChatSyncConversation("chat", "หัวข้อ", Instant.now(), List.of(
                new ChatSyncConversation.Message("message", "tool", "result", List.of(), List.of(),
                        null, null, Instant.now())));
        assertThrows(IllegalArgumentException.class, () -> service.save(session, "chat", tool));
    }

    @Test
    void preservesConversationStateAndBoundedTurnMetadata() {
        ChatSyncConversation input = new ChatSyncConversation("chat", "หัวข้อ", null, List.of(
                new ChatSyncConversation.Message("message", "assistant", "คำตอบ", List.of(), List.of(),
                        null, null, Instant.now(), Map.of(
                                "status", "complete", "feedback", "up", "branchId", "branch-1",
                                "backgroundJobId", "job-1"))),
                true, true);

        ChatSyncConversation saved = service.save(session, "chat", input);

        assertTrue(saved.pinned());
        assertTrue(saved.archived());
        assertEquals("up", saved.messages().getFirst().metadata().get("feedback"));
        assertEquals("job-1", saved.messages().getFirst().metadata().get("backgroundJobId"));
    }

    @Test
    void inMemorySyncReplacesTranscriptOnlyWithNewestSnapshot() {
        InMemoryChatSyncRepository syncRepository = new InMemoryChatSyncRepository();
        Instant firstUpdate = Instant.parse("2026-08-26T00:00:00Z");
        ChatSyncConversation.Message first = new ChatSyncConversation.Message(
                "first", "user", "ต้นฉบับ", List.of(), List.of(), null, null, firstUpdate);
        ChatSyncConversation.Message regenerated = new ChatSyncConversation.Message(
                "regenerated", "assistant", "คำตอบใหม่", List.of(), List.of(), null, null,
                firstUpdate.plusSeconds(1));
        syncRepository.upsert("default", new ChatSyncConversation(
                "chat", "เดิม", firstUpdate, List.of(first)));
        syncRepository.upsert("default", new ChatSyncConversation(
                "chat", "ใหม่", firstUpdate.plusSeconds(2), List.of(first, regenerated)));
        syncRepository.upsert("default", new ChatSyncConversation(
                "chat", "ข้อมูลเก่า", firstUpdate.minusSeconds(1), List.of()));

        ChatSyncConversation saved = syncRepository.list("default", 10).getFirst();
        assertEquals("ใหม่", saved.title());
        assertEquals(List.of("first", "regenerated"),
                saved.messages().stream().map(ChatSyncConversation.Message::id).toList());
    }

    private static final class InMemoryChats implements ChatSyncRepository {
        private final Map<String, ChatSyncConversation> values = new LinkedHashMap<>();
        private String lastOwner;

        @Override public List<ChatSyncConversation> list(String ownerId, int limit) {
            return new ArrayList<>(values.values()).stream().limit(limit).toList();
        }
        @Override public void upsert(String ownerId, ChatSyncConversation conversation) {
            lastOwner = ownerId;
            values.put(conversation.id(), conversation);
        }
        @Override public boolean delete(String ownerId, String conversationId) {
            return values.remove(conversationId) != null;
        }
        @Override public void deleteAll(String ownerId) { values.clear(); }
    }
}
