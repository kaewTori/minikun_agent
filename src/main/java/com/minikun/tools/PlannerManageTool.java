package com.minikun.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerService;

/** Single high-level planner function; persistence and delivery stay in domain services. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class PlannerManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "planner.manage",
            "Create, list, update, or cancel reminders and schedule entries. "
                    + "For create, update, and cancel, first show the proposed change and ask the user for confirmation. "
                    + "Only execute the write after confirmed=true. Convert natural-language dates into ISO-8601 local date-time "
                    + "using Asia/Bangkok unless the user specifies another IANA timezone.",
            Map.of(
                    "action", new ToolParameter("action", ToolParameterType.STRING, true,
                            "One of create, list, update, or cancel."),
                    "title", new ToolParameter("title", ToolParameterType.STRING, false,
                            "Short reminder title."),
                    "note", new ToolParameter("note", ToolParameterType.STRING, false,
                            "Optional details."),
                    "at", new ToolParameter("at", ToolParameterType.STRING, false,
                            "ISO-8601 date/time, preferably with offset, such as 2026-08-20T09:00:00+07:00."),
                    "timezone", new ToolParameter("timezone", ToolParameterType.STRING, false,
                            "IANA timezone, default Asia/Bangkok."),
                    "remind_before_minutes", new ToolParameter("remind_before_minutes", ToolParameterType.INTEGER,
                            false, "Minutes before the event to send the reminder."),
                    "recurrence", new ToolParameter("recurrence", ToolParameterType.STRING, false,
                            "NONE, DAILY, or WEEKLY."),
                    "event_id", new ToolParameter("event_id", ToolParameterType.STRING, false,
                            "Existing reminder UUID for update or cancel."),
                    "confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                            "Must be true to apply create, update, or cancel.")));

    private final PlannerService planner;
    private final PlannerConfirmationService confirmations;

    public PlannerManageTool(PlannerService planner, PlannerConfirmationService confirmations) {
        this.planner = Objects.requireNonNull(planner, "planner service must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "planner confirmations must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        boolean confirmed = booleanValue(arguments, "confirmed");
        try {
            return switch (action) {
                case "create" -> create(context, arguments, confirmed);
                case "list" -> ToolResult.success(Map.of(
                        "action", "list",
                        "events", planner.describeAll(planner.list(context.conversationId()))));
                case "update" -> update(context, arguments, confirmed);
                case "cancel" -> cancel(context, arguments, confirmed);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "planner action must be create, list, update, or cancel");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "planner operation is temporarily unavailable");
        }
    }

    private ToolResult create(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        Map<String, Object> proposed = Map.of(
                "action", "create",
                "title", text(arguments, "title"),
                "at", text(arguments, "at"),
                "timezone", text(arguments, "timezone"),
                "remind_before_minutes", integerValue(arguments, "remind_before_minutes", 0),
                "recurrence", textOr(arguments, "recurrence", "NONE"));
        if (!confirmed) {
            confirmations.save(context.conversationId(), "create", arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", "รายการแจ้งเตือน \"" + text(arguments, "title")
                            + "\" ยังไม่ได้บันทึกครับ พี่สาวยืนยันให้มินิคุงบันทึกไหมครับ",
                    "proposed", proposed));
        }
        var event = planner.create(context.conversationId(), text(arguments, "title"), text(arguments, "note"),
                text(arguments, "at"), text(arguments, "timezone"),
                integerValue(arguments, "remind_before_minutes", 0), textOr(arguments, "recurrence", "NONE"));
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("saved", true, "event", planner.describe(event)));
    }

    private ToolResult update(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        UUID id = id(arguments);
        if (!confirmed) {
            confirmations.save(context.conversationId(), "update", arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", "การแก้ไขแจ้งเตือนรายการ " + id
                            + " ยังไม่ได้บันทึกครับ พี่สาวยืนยันให้มินิคุงดำเนินการไหมครับ",
                    "event_id", id.toString()));
        }
        var event = planner.update(context.conversationId(), id, nullableText(arguments, "title"),
                nullableText(arguments, "note"), nullableText(arguments, "at"),
                nullableText(arguments, "timezone"), nullableInteger(arguments, "remind_before_minutes"),
                nullableText(arguments, "recurrence"));
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("updated", true, "event", planner.describe(event)));
    }

    private ToolResult cancel(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        UUID id = id(arguments);
        if (!confirmed) {
            confirmations.save(context.conversationId(), "cancel", arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", "การยกเลิกแจ้งเตือนรายการ " + id
                            + " ยังไม่ได้ดำเนินการครับ พี่สาวยืนยันให้มินิคุงยกเลิกไหมครับ",
                    "event_id", id.toString()));
        }
        boolean cancelled = planner.cancel(context.conversationId(), id);
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("cancelled", cancelled, "event_id", id.toString()));
    }

    private UUID id(Map<String, Object> arguments) {
        try {
            return UUID.fromString(text(arguments, "event_id"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("event_id must be a valid UUID");
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String textOr(Map<String, Object> arguments, String key, String fallback) {
        String value = text(arguments, key);
        return value.isBlank() ? fallback : value;
    }

    private String nullableText(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : value;
    }

    private int integerValue(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private Integer nullableInteger(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : integerValue(arguments, key, 0);
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }
}
