package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.minikun.goal.NextActionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only tool that recommends the smallest useful next action for active goals. */
@Component
@ConditionalOnProperty(name = "minikun.goal.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(NextActionService.class)
public final class NextActionTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "goal.next_action",
            "Recommend the smallest useful next action for the user's active goals from verified task state. "
                    + "This tool is read-only; never claim that the action was performed.",
            Map.of("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                    "Maximum number of recommendations, between 1 and 10.")));
    private final NextActionService service;

    public NextActionTool(NextActionService service) { this.service = Objects.requireNonNull(service); }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            int limit = arguments != null && arguments.get("limit") instanceof Number number
                    ? number.intValue() : 5;
            if (limit < 1 || limit > 10) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "limit must be between 1 and 10");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recommendations", service.recommend(context.ownerId(), limit));
            result.put("read_only", true);
            return ToolResult.success(result);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "next action is temporarily unavailable");
        }
    }
}
