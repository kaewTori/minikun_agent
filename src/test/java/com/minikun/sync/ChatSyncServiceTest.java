package com.minikun.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
