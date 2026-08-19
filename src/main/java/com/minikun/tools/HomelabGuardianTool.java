package com.minikun.tools;

import com.minikun.guardian.GuardianActionService;
import com.minikun.guardian.HomelabGuardianService;
import com.minikun.planner.PlannerConfirmationService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** One constrained tool for homelab inspection, evidence, backups and allowlisted remediation. */
@Component
@ConditionalOnProperty(name = "minikun.guardian.enabled", havingValue = "true", matchIfMissing = true)
public final class HomelabGuardianTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "homelab.guardian",
            "Inspect and diagnose the homelab using verified host, dependency, redacted log, and backup evidence. "
                    + "Use inspect for an overall diagnosis, logs for an allowlisted log source, backups for backup health, "
                    + "actions to list safe configured remediations, execute to run one allowlisted action, and audit for history. "
                    + "Never invent shell commands. Execute always requires explicit user confirmation before confirmed=true.",
            parameters());

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> result = new LinkedHashMap<>();
        result.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "One of inspect, logs, backups, actions, execute, or audit."));
        result.put("source", new ToolParameter("source", ToolParameterType.STRING, false,
                "Configured log source name for logs. Leave blank to list allowed sources."));
        result.put("lines", new ToolParameter("lines", ToolParameterType.INTEGER, false,
                "Number of redacted log lines, 1 to 200. Default 80."));
        result.put("action_id", new ToolParameter("action_id", ToolParameterType.STRING, false,
                "Exact remediation action id returned by actions."));
        result.put("confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                "Must be true only after explicit user confirmation for execute."));
        result.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                "Audit record limit, 1 to 200. Default 20."));
        return Map.copyOf(result);
    }

    private final HomelabGuardianService guardian;
    private final GuardianActionService actions;
    private final Optional<PlannerConfirmationService> confirmations;

    public HomelabGuardianTool(
            HomelabGuardianService guardian,
            GuardianActionService actions,
            Optional<PlannerConfirmationService> confirmations) {
        this.guardian = Objects.requireNonNull(guardian, "guardian service must not be null");
        this.actions = Objects.requireNonNull(actions, "guardian actions must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "guardian confirmations must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            return switch (text(arguments, "action").toLowerCase(java.util.Locale.ROOT)) {
                case "inspect" -> ToolResult.success(guardian.inspect());
                case "logs" -> logs(arguments);
                case "backups" -> ToolResult.success(Map.of("backups", guardian.backups()));
                case "actions" -> ToolResult.success(Map.of("actions", actions.available(),
                        "commands_exposed", false, "confirmation_required", true));
                case "execute" -> executeAction(context, arguments);
                case "audit" -> ToolResult.success(Map.of("audit", actions.audit(context.ownerId(),
                        integer(arguments, "limit", 20))));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "guardian action must be inspect, logs, backups, actions, execute, or audit");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "homelab guardian operation is temporarily unavailable");
        }
    }

    private ToolResult logs(Map<String, Object> arguments) {
        String source = text(arguments, "source");
        if (source.isBlank()) {
            return ToolResult.success(Map.of("allowed_sources", guardian.logSources()));
        }
        return ToolResult.success(guardian.logs(source, integer(arguments, "lines", 80)));
    }

    private ToolResult executeAction(ToolCallContext context, Map<String, Object> arguments) {
        String actionId = text(arguments, "action_id");
        var definition = actions.require(actionId);
        if (!booleanValue(arguments, "confirmed")) {
            if (confirmations.isEmpty()) {
                return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                        "guardian confirmation storage is unavailable; no action was executed");
            }
            actions.requested(context, actionId);
            confirmations.get().save(context.conversationId(), context.ownerId(), "guardian.execute", arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", "มินิคุงเตรียม action \"" + definition.description()
                            + "\" ไว้แล้ว แต่ยังไม่ได้สั่งงานครับ ยืนยันให้ดำเนินการไหมครับ",
                    "proposed", Map.of("action_id", definition.id(), "description", definition.description())));
        }
        Map<String, Object> result = actions.execute(context, actionId);
        confirmations.ifPresent(value -> value.clear(context.conversationId()));
        return ToolResult.success(result);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) return fallback;
        int result = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        if (result < 1 || result > 200) throw new IllegalArgumentException(key + " must be between 1 and 200");
        return result;
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }
}
