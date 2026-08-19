package com.minikun.tools;

import java.util.Map;

/** Shared argument schema for planner-backed schedule tools. */
final class ScheduleToolSchema {
    private ScheduleToolSchema() {
    }

    static Map<String, ToolParameter> parameters() {
        return Map.of(
                "action", new ToolParameter("action", ToolParameterType.STRING, true,
                        "One of create, list, update, cancel, acknowledge, or snooze."),
                "title", new ToolParameter("title", ToolParameterType.STRING, false,
                        "Short event or reminder title."),
                "note", new ToolParameter("note", ToolParameterType.STRING, false,
                        "Optional event details."),
                "at", new ToolParameter("at", ToolParameterType.STRING, false,
                        "ISO-8601 date/time, preferably with offset, such as 2026-08-20T09:00:00+07:00."),
                "timezone", new ToolParameter("timezone", ToolParameterType.STRING, false,
                        "IANA timezone, default Asia/Bangkok."),
                "remind_before_minutes", new ToolParameter("remind_before_minutes", ToolParameterType.INTEGER,
                        false, "Minutes before the event to send a reminder."),
                "recurrence", new ToolParameter("recurrence", ToolParameterType.STRING, false,
                        "NONE, DAILY, or WEEKLY."),
                "event_id", new ToolParameter("event_id", ToolParameterType.STRING, false,
                        "Existing event UUID for update or cancel."),
                "confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                        "Must be true to apply create, update, cancel, or snooze."));
    }
}
