package com.minikun.guardian;

import com.minikun.tools.ToolCallContext;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runs only immutable, application-configured actions after the tool layer confirms consent. */
public final class GuardianActionService {
    private final Map<String, GuardianActionDefinition> actions;
    private final GuardianCommandRunner runner;
    private final GuardianAuditStore audit;
    private final Clock clock;
    private final Duration timeout;

    public GuardianActionService(
            List<GuardianActionDefinition> actions,
            GuardianCommandRunner runner,
            GuardianAuditStore audit,
            Clock clock,
            Duration timeout) {
        Map<String, GuardianActionDefinition> configured = new LinkedHashMap<>();
        for (GuardianActionDefinition action : actions == null ? List.<GuardianActionDefinition>of() : actions) {
            if (configured.putIfAbsent(action.id(), action) != null) {
                throw new IllegalArgumentException("duplicate guardian action: " + action.id());
            }
        }
        this.actions = Map.copyOf(configured);
        this.runner = Objects.requireNonNull(runner, "guardian command runner must not be null");
        this.audit = Objects.requireNonNull(audit, "guardian audit store must not be null");
        this.clock = Objects.requireNonNull(clock, "guardian action clock must not be null");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("guardian action timeout must be positive");
        }
        this.timeout = timeout;
    }

    public List<Map<String, String>> available() {
        return actions.values().stream()
                .sorted(java.util.Comparator.comparing(GuardianActionDefinition::id))
                .map(value -> Map.of("action_id", value.id(), "description", value.description()))
                .toList();
    }

    public GuardianActionDefinition require(String actionId) {
        GuardianActionDefinition action = actions.get(normalize(actionId));
        if (action == null) {
            throw new IllegalArgumentException("action_id is not in the guardian allowlist");
        }
        return action;
    }

    public void requested(ToolCallContext context, String actionId) {
        GuardianActionDefinition action = require(actionId);
        audit.save(record(context, action.id(), "REQUESTED", "awaiting explicit confirmation"));
    }

    public Map<String, Object> execute(ToolCallContext context, String actionId) {
        GuardianActionDefinition action = require(actionId);
        GuardianCommandResult result = runner.run(action.command(), timeout);
        String status = result.successful() ? "SUCCEEDED" : result.timedOut() ? "TIMED_OUT" : "FAILED";
        audit.save(record(context, action.id(), status,
                result.successful() ? "configured action completed" : "configured action did not complete successfully"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action_id", action.id());
        response.put("description", action.description());
        response.put("status", status);
        response.put("success", result.successful());
        response.put("exit_code", result.exitCode());
        response.put("timed_out", result.timedOut());
        return Map.copyOf(response);
    }

    public List<GuardianActionAudit> audit(String ownerId, int limit) {
        return audit.list(ownerId, limit);
    }

    private GuardianActionAudit record(ToolCallContext context, String actionId, String status, String detail) {
        return new GuardianActionAudit(UUID.randomUUID(), context.ownerId(), context.conversationId().value(),
                actionId, status, clock.instant(), detail);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
