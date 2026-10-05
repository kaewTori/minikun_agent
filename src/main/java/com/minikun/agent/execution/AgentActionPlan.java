package com.minikun.agent.execution;

import java.util.List;
import java.util.Map;

/** Explicit tool steps; completion refers to these observable criteria, not arbitrary model prose. */
public record AgentActionPlan(String objective, List<Step> steps, int timeoutSeconds) {
    public AgentActionPlan {
        if (objective == null || objective.isBlank() || objective.length() > 2000) throw new IllegalArgumentException("objective required, at most 2000 characters");
        steps = List.copyOf(steps == null ? List.of() : steps);
        if (steps.isEmpty() || steps.size() > 8) throw new IllegalArgumentException("plan requires 1–8 steps");
        timeoutSeconds = timeoutSeconds == 0 ? 600 : timeoutSeconds;
        if (timeoutSeconds < 10 || timeoutSeconds > 3600) throw new IllegalArgumentException("timeoutSeconds must be 10–3600");
    }

    public record Step(String title, String tool, Map<String, Object> arguments, Check verify, Check skipIf) {
        public Step {
            if (title == null || title.isBlank() || title.length() > 300 || tool == null || tool.isBlank()) throw new IllegalArgumentException("step title and tool required");
            arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
            if (arguments.containsKey("confirmed") || arguments.containsKey("expected_sha256")) throw new IllegalArgumentException("consent and fingerprints are server-owned");
            if (verify == null && "computer.local".equals(tool) && "workflow".equals(arguments.get("action"))) {
                verify = new Check("computer.local", Map.of("action", "workflow_status", "workflow_id", arguments.get("workflow_id")), "/verified", true);
            }
        }
    }

    public record Check(String tool, Map<String, Object> arguments, String pointer, Object expected) {
        public Check {
            arguments = Map.copyOf(arguments == null ? Map.of() : arguments);
            if (tool == null || pointer == null || !pointer.startsWith("/") || expected == null
                    || !(expected instanceof String || expected instanceof Number || expected instanceof Boolean)) throw new IllegalArgumentException("check requires a read-only tool, JSON pointer and scalar expected value");
        }
    }
}
