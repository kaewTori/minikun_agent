package com.minikun.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChatSyncConversation(
        String id,
        String title,
        Instant updatedAt,
        List<Message> messages) {

    public record Message(
            String id,
            String role,
            String content,
            List<String> files,
            List<Map<String, Object>> attachments,
            Map<String, Object> usage,
            Map<String, Object> timing,
            Instant createdAt) {
    }
}
