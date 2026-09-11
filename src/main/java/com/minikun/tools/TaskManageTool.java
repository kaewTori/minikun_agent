package com.minikun.tools;

import com.minikun.planner.PlannerConfirmationService;
import com.minikun.task.TaskPatch;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Owner-scoped goal/task management with confirmation for state-changing actions. */
@Component
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({TaskService.class, PlannerConfirmationService.class})
public final class TaskManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task.manage",
            "Manage owner-scoped personal tasks and goals. Use list to inspect open work, create to capture work, "
                    + "update to set the next action or blocked state, complete when done, and cancel to discard. "
                    + "Ask for confirmation before create, update, complete, or cancel. The owner comes from chat context.",
            parameters());

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> parameters = new java.util.LinkedHashMap<>();
        parameters.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                            "One of create, list, update, complete, or cancel."));
        parameters.put("kind", new ToolParameter("kind", ToolParameterType.STRING, false,
                            "TASK or GOAL; defaults to TASK."));
        parameters.put("title", new ToolParameter("title", ToolParameterType.STRING, false, "Task or goal title."));
        parameters.put("description", new ToolParameter("description", ToolParameterType.STRING, false,
                            "Optional task details."));
        parameters.put("task_id", new ToolParameter("task_id", ToolParameterType.STRING, false,
                            "Existing task UUID for update, complete, or cancel."));
        parameters.put("status", new ToolParameter("status", ToolParameterType.STRING, false,
                            "OPEN, IN_PROGRESS, BLOCKED, DONE, or CANCELLED."));
        parameters.put("due_at", new ToolParameter("due_at", ToolParameterType.STRING, false,
                            "Optional ISO-8601 due date/time."));
        parameters.put("timezone", new ToolParameter("timezone", ToolParameterType.STRING, false,
                            "IANA timezone, default Asia/Bangkok."));
        parameters.put("next_action", new ToolParameter("next_action", ToolParameterType.STRING, false,
                            "The smallest next action."));
        parameters.put("waiting_for", new ToolParameter("waiting_for", ToolParameterType.STRING, false,
                            "Person or dependency this task is waiting for."));
        parameters.put("follow_up_at", new ToolParameter("follow_up_at", ToolParameterType.STRING, false,
                            "When Mini-kun should follow up if the task remains open."));
        parameters.put("parent_id", new ToolParameter("parent_id", ToolParameterType.STRING, false,
                "Optional parent goal UUID."));
        parameters.put("goal_id", new ToolParameter("goal_id", ToolParameterType.STRING, false,
                "Optional explicit goal UUID this task advances."));
        parameters.put("confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                            "Must be true to apply a write."));
        return Map.copyOf(parameters);
    }

    private final TaskService tasks;
    private final PlannerConfirmationService confirmations;

    public TaskManageTool(TaskService tasks, PlannerConfirmationService confirmations) {
        this.tasks = Objects.requireNonNull(tasks, "task service must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmation service must not be null");
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
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        boolean confirmed = booleanValue(arguments, "confirmed");
        try {
            return switch (action) {
                case "list" -> list(context, arguments);
                case "create" -> create(context, arguments, confirmed);
                case "update" -> update(context, arguments, confirmed);
                case "complete" -> complete(context, arguments, confirmed);
                case "cancel" -> cancel(context, arguments, confirmed);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "task action must be create, list, update, complete, or cancel");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "task operation is temporarily unavailable");
        }
    }

    private ToolResult list(ToolCallContext context, Map<String, Object> arguments) {
        String status = text(arguments, "status");
        TaskStatus parsed = status.isBlank() ? null : TaskStatus.parse(status);
        return ToolResult.success(Map.of("action", "list", "owner_id", context.ownerId(),
                "tasks", tasks.describeAll(tasks.list(context.ownerId(), parsed))));
    }

    private ToolResult create(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "task.create", arguments);
            return ToolResult.success(Map.of("requires_confirmation", true,
                    "message", "ผมเตรียมบันทึกงานนี้ไว้แล้ว ยืนยันให้มินิคุงบันทึกไหมครับ",
                    "proposed", Map.of("kind", textOr(arguments, "kind", "TASK"),
                            "title", text(arguments, "title"), "next_action", text(arguments, "next_action"))));
        }
        var task = tasks.create(context.ownerId(), context.conversationId().value(), text(arguments, "kind"),
                text(arguments, "title"), text(arguments, "description"), text(arguments, "due_at"),
                text(arguments, "timezone"), text(arguments, "next_action"), text(arguments, "waiting_for"),
                text(arguments, "follow_up_at"), text(arguments, "parent_id"), text(arguments, "goal_id"));
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("saved", true, "task", tasks.describe(task)));
    }

    private ToolResult update(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        UUID id = id(arguments);
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "task.update", arguments);
            return ToolResult.success(Map.of("requires_confirmation", true,
                    "message", "ผมเตรียมแก้ไขงานนี้แล้ว ยืนยันให้มินิคุงดำเนินการไหมครับ",
                    "task_id", id.toString()));
        }
        var task = tasks.update(context.ownerId(), id, patch(arguments));
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("updated", true, "task", tasks.describe(task)));
    }

    private ToolResult complete(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        UUID id = id(arguments);
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "task.complete", arguments);
            return ToolResult.success(Map.of("requires_confirmation", true,
                    "message", "ยืนยันว่าทำงานนี้เสร็จแล้วใช่ไหมครับ", "task_id", id.toString()));
        }
        var task = tasks.complete(context.ownerId(), id);
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("completed", true, "task", tasks.describe(task)));
    }

    private ToolResult cancel(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        UUID id = id(arguments);
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "task.cancel", arguments);
            return ToolResult.success(Map.of("requires_confirmation", true,
                    "message", "ยืนยันให้ยกเลิกงานนี้ไหมครับ", "task_id", id.toString()));
        }
        boolean cancelled = tasks.cancel(context.ownerId(), id);
        confirmations.clear(context.conversationId());
        if (!cancelled) return ToolResult.failure(ToolErrorCode.OUTCOME_UNVERIFIED,
                "operation did not change the requested record; inspect before retrying");
        return ToolResult.success(Map.of("cancelled", cancelled, "task_id", id.toString()));
    }

    private TaskPatch patch(Map<String, Object> arguments) {
        String status = text(arguments, "status");
        return new TaskPatch(nullable(arguments, "title"), nullable(arguments, "description"),
                status.isBlank() ? null : TaskStatus.parse(status), instant(arguments, "due_at"),
                zone(arguments, "timezone"), nullable(arguments, "next_action"), nullable(arguments, "waiting_for"),
                instant(arguments, "follow_up_at"), nullableUuid(arguments, "goal_id"));
    }

    private java.time.Instant instant(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : java.time.Instant.parse(value);
    }

    private java.time.ZoneId zone(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : java.time.ZoneId.of(value);
    }

    private UUID id(Map<String, Object> arguments) {
        try { return UUID.fromString(text(arguments, "task_id")); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("task_id must be a valid UUID"); }
    }

    private UUID nullableUuid(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        if (value.isBlank()) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException(key + " must be a valid UUID"); }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String nullable(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : value;
    }

    private String textOr(Map<String, Object> arguments, String key, String fallback) {
        String value = text(arguments, key);
        return value.isBlank() ? fallback : value;
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }
}
