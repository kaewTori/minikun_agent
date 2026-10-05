package com.minikun.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.tools.*;
import java.util.Map;
import java.util.UUID;

/** Chat entry point. Approvals and standing grants are intentionally absent from the model schema. */
public final class AgentActionTool implements Tool {
    private final AgentActionRuntime runtime;
    private final ObjectMapper mapper;
    public AgentActionTool(AgentActionRuntime runtime, ObjectMapper mapper) { this.runtime = runtime; this.mapper = mapper; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition("agent.action", "Run a durable multi-step action plan, verify real outcomes, and continue after user approval. "
                + "Use catalog before selecting configured actions/workflows. Use repair for homelab component + action_id; "
                + "start accepts plan={objective,timeoutSeconds,steps:[{title,tool,arguments,verify?,skipIf?}]}. "
                + "Checks are {tool,arguments,pointer,expected} using read-only tools and scalar expected values. "
                + "File write/move/trash have automatic read-back; other writes require independent verify checks. "
                + "Use status/cancel with run_id. Never approve your own actions or invent completion.", Map.of(
                "action", new ToolParameter("action", ToolParameterType.STRING, true, "catalog, repair, start, status, cancel"),
                "plan", new ToolParameter("plan", ToolParameterType.OBJECT, false, "Explicit plan and observable criteria"),
                "component", new ToolParameter("component", ToolParameterType.STRING, false, "Named dependency for repair"),
                "action_id", new ToolParameter("action_id", ToolParameterType.STRING, false, "Configured guardian action"),
                "run_id", new ToolParameter("run_id", ToolParameterType.STRING, false, "Existing action run UUID")));
    }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> args) { return !java.util.Set.of("catalog", "status").contains(String.valueOf(args.get("action"))); }
    @Override public ToolResult execute(ToolCallContext context, Map<String, Object> args) {
        try {
            return ToolResult.success(switch (String.valueOf(args.get("action"))) {
                case "catalog" -> runtime.catalog();
                case "status" -> runtime.view(context.ownerId(), UUID.fromString(String.valueOf(args.get("run_id"))));
                case "cancel" -> runtime.cancel(context.ownerId(), UUID.fromString(String.valueOf(args.get("run_id"))));
                case "start", "repair" -> {
                    AgentActionPlan plan = "repair".equals(args.get("action"))
                            ? AgentActionRuntime.repairPlan(String.valueOf(args.get("component")), String.valueOf(args.get("action_id")))
                            : mapper.convertValue(args.get("plan"), AgentActionPlan.class);
                    var state = runtime.start(context.ownerId(), context.conversationId().value(), context.callId(), plan);
                    yield Map.of("run_id", state.runId(), "status", "PLANNED", "message", "รับงานแล้ว ติดตามผลและอนุมัติขั้นที่จำเป็นได้ใน Cockpit");
                }
                default -> throw new IllegalArgumentException("unsupported agent action");
            });
        } catch (IllegalArgumentException | IllegalStateException e) { return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, e.getMessage()); }
    }
}
