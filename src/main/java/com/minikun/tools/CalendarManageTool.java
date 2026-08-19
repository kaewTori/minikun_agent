package com.minikun.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.minikun.calendar.ExternalCalendarService;

/** Calendar-facing name for the existing planner-backed schedule capability. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class CalendarManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "calendar.manage",
            "Manage the user's calendar and agenda. Lists combine local planner entries with the configured "
                    + "read-only external iCalendar feed. "
                    + "Use create, list, update, or cancel. Ask for confirmation before any write. "
                    + "Use ISO-8601 date/time and Asia/Bangkok unless another IANA timezone is specified.",
            ScheduleToolSchema.parameters());

    private final PlannerManageTool planner;
    private final ObjectProvider<ExternalCalendarService> externalCalendar;
    private final Clock clock;

    public CalendarManageTool(PlannerManageTool planner) {
        this(planner, null, Clock.systemUTC());
    }

    @Autowired
    public CalendarManageTool(
            PlannerManageTool planner,
            ObjectProvider<ExternalCalendarService> externalCalendar,
            Clock clock) {
        this.planner = Objects.requireNonNull(planner, "planner tool must not be null");
        this.externalCalendar = externalCalendar;
        this.clock = Objects.requireNonNull(clock, "calendar clock must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        ToolResult local = planner.execute(context, arguments);
        if (!local.success() || !"list".equalsIgnoreCase(text(arguments, "action")) || externalCalendar == null) {
            return local;
        }
        ExternalCalendarService external = externalCalendar.getIfAvailable();
        if (external == null) return local;
        try {
            Instant now = clock.instant();
            Map<String, Object> combined = new LinkedHashMap<>((Map<String, Object>) local.value());
            combined.put("external_events", external.events(now, now.plus(Duration.ofDays(14))));
            combined.put("external_read_only", true);
            return ToolResult.success(combined);
        } catch (RuntimeException exception) {
            Map<String, Object> combined = new LinkedHashMap<>((Map<String, Object>) local.value());
            combined.put("external_events", java.util.List.of());
            combined.put("external_calendar_warning", "external calendar is temporarily unavailable");
            return ToolResult.success(combined);
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
