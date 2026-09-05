package com.minikun.tools;

import com.minikun.memory.management.MemoryManagementService;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemoryUpdate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Explicit, owner-scoped memory actions for the personal assistant. */
@Component
@ConditionalOnBean(MemoryManagementService.class)
public final class MemoryManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "memory.manage",
            "Manage explicit long-term memories for the current user. "
                    + "Use remember when the user says to remember something, list to inspect memories, "
                    + "update to correct one memory, and forget to delete one memory by its UUID. Never invent a memory UUID. "
                    + "The owner is taken from the authenticated chat context.",
            Map.of(
                    "action", new ToolParameter("action", ToolParameterType.STRING, true,
                            "One of remember, list, update, or forget."),
                    "content", new ToolParameter("content", ToolParameterType.STRING, false,
                            "The fact or preference to remember."),
                    "category", new ToolParameter("category", ToolParameterType.STRING, false,
                            "PREFERENCE, GOAL, PROFILE, SKILL, PROJECT, or EPISODE. Defaults to PROFILE."),
                    "memory_id", new ToolParameter("memory_id", ToolParameterType.STRING, false,
                            "Existing memory UUID for forget."),
                    "limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                            "Maximum memories to list, default 100.")));

    private final MemoryManagementService memories;

    public MemoryManageTool(MemoryManagementService memories) {
        this.memories = Objects.requireNonNull(memories, "memory management service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return !"list".equalsIgnoreCase(text(arguments, "action"));
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "remember" -> remember(context, arguments);
                case "list" -> list(context, arguments);
                case "update" -> update(context, arguments);
                case "forget" -> forget(context, arguments);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "memory action must be remember, list, update, or forget");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "memory operation is temporarily unavailable");
        }
    }

    private ToolResult remember(ToolCallContext context, Map<String, Object> arguments) {
        MemoryCategory category = category(arguments);
        String content = text(arguments, "content");
        boolean saved = memories.remember(context.ownerId(), context.conversationId().value(), category, content);
        return ToolResult.success(Map.of(
                "action", "remember",
                "saved", saved,
                "category", category.name(),
                "content", content));
    }

    private ToolResult list(ToolCallContext context, Map<String, Object> arguments) {
        int limit = integer(arguments, "limit", 100);
        return ToolResult.success(Map.of(
                "action", "list",
                "owner_id", context.ownerId(),
                "memories", memories.list(context.ownerId(), limit)));
    }

    private ToolResult forget(ToolCallContext context, Map<String, Object> arguments) {
        String value = text(arguments, "memory_id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("memory_id is required for forget");
        }
        MemoryId id;
        try {
            id = new MemoryId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("memory_id must be a valid UUID");
        }
        return ToolResult.success(Map.of(
                "action", "forget",
                "memory_id", value,
                "deleted", memories.delete(context.ownerId(), id)));
    }

    private ToolResult update(ToolCallContext context, Map<String, Object> arguments) {
        String value = text(arguments, "memory_id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("memory_id is required for update");
        }
        MemoryId id;
        try {
            id = new MemoryId(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("memory_id must be a valid UUID");
        }
        MemoryCategory category = category(arguments);
        String content = text(arguments, "content");
        boolean updated = memories.update(context.ownerId(), id,
                new MemoryUpdate(category, content, 1.0, "ผู้ใช้แก้ไข memory โดยตรง"));
        return ToolResult.success(Map.of("action", "update", "memory_id", value, "updated", updated));
    }

    private MemoryCategory category(Map<String, Object> arguments) {
        String value = text(arguments, "category");
        if (value.isBlank()) {
            return MemoryCategory.PROFILE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "preference", "preferences", "ความชอบ" -> MemoryCategory.PREFERENCE;
            case "goal", "เป้าหมาย" -> MemoryCategory.GOAL;
            case "profile", "ข้อมูลส่วนตัว" -> MemoryCategory.PROFILE;
            case "skill", "ทักษะ" -> MemoryCategory.SKILL;
            case "project", "โปรเจกต์", "โครงการ" -> MemoryCategory.PROJECT;
            case "episode", "เหตุการณ์", "เรื่องสำคัญ" -> MemoryCategory.EPISODE;
            default -> throw new IllegalArgumentException(
                    "category must be PREFERENCE, GOAL, PROFILE, SKILL, PROJECT, or EPISODE");
        };
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
