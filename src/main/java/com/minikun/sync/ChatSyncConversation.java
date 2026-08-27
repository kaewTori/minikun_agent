package com.minikun.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChatSyncConversation(
        String id,
        String title,
        Instant updatedAt,
        List<Message> messages,
        boolean pinned,
        boolean archived) {

    public ChatSyncConversation(String id, String title, Instant updatedAt, List<Message> messages) {
        this(id, title, updatedAt, messages, false, false);
    }

    public record Message(
            String id,
            String role,
            String content,
            List<String> files,
            List<Map<String, Object>> attachments,
            Map<String, Object> usage,
            Map<String, Object> timing,
            Instant createdAt,
            Map<String, Object> metadata) {

        public Message(String id, String role, String content, List<String> files,
                List<Map<String, Object>> attachments, Map<String, Object> usage,
                Map<String, Object> timing, Instant createdAt) {
            this(id, role, content, files, attachments, usage, timing, createdAt, Map.of());
        }
    }
}
