package com.minikun.personalloop;

import com.minikun.tools.Tool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;
import com.minikun.tools.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only model access to the closed-loop personal state. Mutations remain explicit REST confirmations. */
public final class PersonalLoopTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "personal.loop",
            "Read the owner's weekly reviews, learned recommendation outcomes, personal timeline, automation runs, "
                    + "and homelab incidents. This tool is read-only and must never claim it changed any state.",
            Map.of(
                    "action", new ToolParameter("action", ToolParameterType.STRING, true,
                            "One of status, weekly_reviews, outcomes, timeline, automation_runs, or incidents."),
                    "limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                            "Maximum results from 1 to 50; defaults to 10.")));
    private final WeeklyReviewService reviews;
    private final OutcomeLearningService outcomes;
    private final UniversalInboxService inbox;
    private final SafeAutomationService automations;
    private final IncidentCommanderService incidents;
    private final PersonalTimelineService timeline;

    public PersonalLoopTool(WeeklyReviewService reviews, OutcomeLearningService outcomes,
            UniversalInboxService inbox, SafeAutomationService automations,
            IncidentCommanderService incidents, PersonalTimelineService timeline) {
        this.reviews = reviews; this.outcomes = outcomes; this.inbox = inbox;
        this.automations = automations; this.incidents = incidents; this.timeline = timeline;
    }

    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            String action = text(arguments, "action").toLowerCase(Locale.ROOT);
            int limit = integer(arguments, "limit", 10);
            if (limit < 1 || limit > 50) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "limit must be between 1 and 50");
            String owner = context.ownerId();
            Object result = switch (action) {
                case "status" -> status(owner);
                case "weekly_reviews" -> reviews.list(owner, limit);
                case "outcomes" -> outcomes.list(owner, limit);
                case "timeline" -> timeline.list(owner, null, null, limit);
                case "automation_runs" -> automations.runs(owner, limit);
                case "incidents" -> incidents.list(owner, limit);
                default -> throw new IllegalArgumentException("action must be status, weekly_reviews, outcomes, timeline, automation_runs, or incidents");
            };
            return ToolResult.success(Map.of("action", action, "result", result, "read_only", true));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "personal loop is temporarily unavailable");
        }
    }

    private Map<String, Object> status(String owner) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latest_weekly_review", reviews.list(owner, 1));
        result.put("outcome_insights", outcomes.insights(owner));
        result.put("inbox_awaiting_confirmation", inbox.list(owner, 100).stream()
                .filter(value -> value.status() == PersonalLoopModels.InboxStatus.PREVIEW).count());
        result.put("automation_waiting_confirmation", automations.runs(owner, 100).stream()
                .filter(value -> value.status() == PersonalLoopModels.AutomationRunStatus.WAITING_CONFIRMATION).count());
        result.put("open_incidents", incidents.list(owner, 100).stream()
                .filter(value -> value.status() == PersonalLoopModels.IncidentStatus.OPEN).count());
        return Map.copyOf(result);
    }

    private String text(Map<String,Object> arguments, String key) { Object value = arguments == null ? null : arguments.get(key); return value == null ? "" : value.toString().trim(); }
    private int integer(Map<String,Object> arguments, String key, int fallback) { Object value = arguments == null ? null : arguments.get(key); return value instanceof Number n ? n.intValue() : value == null ? fallback : Integer.parseInt(value.toString()); }
}
