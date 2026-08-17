package com.minikun.tools;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Returns the current date and time for a requested IANA timezone. */
@Component
public final class CurrentTimeTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "time.get_current_time",
            "Get the current local date and time. Use this for today, now, time, date, "
                    + "day-of-week, and timezone questions. Prefer an IANA timezone when known.",
            Map.of(
                    "timezone", new ToolParameter("timezone", ToolParameterType.STRING, false,
                            "IANA timezone such as Asia/Bangkok, Europe/London, or America/New_York.")));

    private final Clock clock;
    private final String defaultTimezone;

    public CurrentTimeTool(
            Clock clock,
            @Value("${minikun.time.default-zone:Asia/Bangkok}") String defaultTimezone) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultTimezone = Objects.requireNonNullElse(defaultTimezone, "Asia/Bangkok");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String requestedTimezone = text(arguments, "timezone");
        String timezone = requestedTimezone.isBlank() ? defaultTimezone : requestedTimezone;
        try {
            ZoneId zone = ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timezone", zone.getId());
            result.put("localDate", now.toLocalDate().toString());
            result.put("localTime", now.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME));
            result.put("dayOfWeek", now.getDayOfWeek().toString());
            result.put("utcOffset", now.getOffset().toString());
            result.put("iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            return ToolResult.success(result);
        } catch (DateTimeException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                    "unknown timezone: " + timezone);
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
