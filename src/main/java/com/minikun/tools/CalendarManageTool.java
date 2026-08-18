package com.minikun.tools;

import java.util.Map;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Calendar-facing name for the existing planner-backed schedule capability. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class CalendarManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "calendar.manage",
            "Manage the user's local calendar and agenda using the planner-backed schedule store. "
                    + "Use create, list, update, or cancel. Ask for confirmation before any write. "
                    + "Use ISO-8601 date/time and Asia/Bangkok unless another IANA timezone is specified. "
                    + "This is the local calendar layer and can be synchronized with an external calendar later.",
            ScheduleToolSchema.parameters());

    private final PlannerManageTool planner;

    public CalendarManageTool(PlannerManageTool planner) {
        this.planner = Objects.requireNonNull(planner, "planner tool must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        return planner.execute(context, arguments);
    }
}
