package com.minikun.sync;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ChatSyncService {
    private static final Set<String> ROLES = Set.of("user", "assistant", "system");
    private static final Set<String> METADATA_KEYS = Set.of(
            "parentId", "branchId", "status", "feedback", "feedbackReason", "sources");
    private final ChatSyncRepository repository;
    private final SyncEventBroker events;
    private final Clock clock;

    public ChatSyncService(ChatSyncRepository repository, SyncEventBroker events, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.events = Objects.requireNonNull(events);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<ChatSyncConversation> list(DevicePairingService.Session session) {
        return repository.list(session.ownerId(), 40);
    }

    public ChatSyncConversation save(DevicePairingService.Session session,
            String conversationId, ChatSyncConversation value) {
        ChatSyncConversation sanitized = sanitize(conversationId, value);
        repository.upsert(session.ownerId(), sanitized);
        events.publish(session.ownerId(), "conversation", session.deviceId());
        return sanitized;
    }

    public int importAll(DevicePairingService.Session session, List<ChatSyncConversation> values) {
        if (values == null || values.isEmpty()) return 0;
        int imported = 0;
        for (ChatSyncConversation value : values.stream().limit(40).toList()) {
            repository.upsert(session.ownerId(), sanitize(value == null ? "" : value.id(), value));
            imported++;
        }
        if (imported > 0) events.publish(session.ownerId(), "import", session.deviceId());
        return imported;
    }

    public void deleteAll(DevicePairingService.Session session) {
        repository.deleteAll(session.ownerId());
        events.publish(session.ownerId(), "delete", session.deviceId());
    }

    public boolean delete(DevicePairingService.Session session, String conversationId) {
        boolean deleted = repository.delete(session.ownerId(), required(conversationId, "conversation id", 200));
        if (deleted) events.publish(session.ownerId(), "delete", session.deviceId());
        return deleted;
    }

    private ChatSyncConversation sanitize(String pathId, ChatSyncConversation value) {
        if (value == null) throw new IllegalArgumentException("conversation is required");
        String id = required(pathId, "conversation id", 200);
        if (!id.equals(value.id())) throw new IllegalArgumentException("conversation id does not match path");
        String title = required(Objects.requireNonNullElse(value.title(), "แชตใหม่"), "title", 160);
        Instant updatedAt = value.updatedAt() == null ? clock.instant() : value.updatedAt();
        List<ChatSyncConversation.Message> messages = new ArrayList<>();
        List<ChatSyncConversation.Message> input = value.messages() == null ? List.of() : value.messages();
        for (ChatSyncConversation.Message message : input.stream().skip(Math.max(0, input.size() - 80)).toList()) {
            if (message == null) continue;
            String role = required(message.role(), "message role", 20).toLowerCase(Locale.ROOT);
            if (!ROLES.contains(role)) throw new IllegalArgumentException("message role is invalid");
            String messageId = Objects.requireNonNullElse(message.id(), "").trim();
            if (messageId.isBlank()) messageId = "sync-" + UUID.randomUUID();
            messageId = required(messageId, "message id", 200);
            String content = Objects.requireNonNullElse(message.content(), "");
            if (content.length() > 100_000) throw new IllegalArgumentException("message content is too long");
            List<String> files = message.files() == null ? List.of() : message.files().stream()
                    .filter(Objects::nonNull).map(file -> trim(file, 240)).limit(12).toList();
            List<Map<String, Object>> attachments = message.attachments() == null
                    ? List.of() : message.attachments().stream().filter(Objects::nonNull).limit(12).toList();
            Map<String, Object> metadata = sanitizeMetadata(message.metadata());
            messages.add(new ChatSyncConversation.Message(messageId, role, content, files, attachments,
                    message.usage(), message.timing(),
                    message.createdAt() == null ? updatedAt : message.createdAt(), metadata));
        }
        return new ChatSyncConversation(id, title, updatedAt, List.copyOf(messages),
                value.pinned(), value.archived());
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String key : METADATA_KEYS) {
            Object value = input.get(key);
            if (value instanceof String text) {
                result.put(key, trim(text, "feedbackReason".equals(key) ? 500 : 240));
            } else if ("sources".equals(key) && value instanceof List<?> sources) {
                List<Map<String, Object>> sanitized = new ArrayList<>();
                for (Object source : sources.stream().limit(12).toList()) {
                    if (!(source instanceof Map<?, ?> map)) continue;
                    String url = trim(Objects.toString(map.get("url"), ""), 2_000);
                    String name = trim(Objects.toString(map.get("title"), ""), 240);
                    if (!url.isBlank()) sanitized.add(Map.of("url", url, "title", name));
                }
                result.put(key, List.copyOf(sanitized));
            }
        }
        return Map.copyOf(result);
    }

    private String required(String value, String label, int limit) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || normalized.length() > limit) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private String trim(String value, int maximum) {
        String normalized = value.trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
