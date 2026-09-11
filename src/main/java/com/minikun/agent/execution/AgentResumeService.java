package com.minikun.agent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolCall;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolExecutor;
import com.minikun.tools.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Replays persisted reads and stops at writes or blocked dependencies for outcome review. */
@Service
@ConditionalOnProperty(name = "minikun.agent.execution.enabled", havingValue = "true", matchIfMissing = true)
public final class AgentResumeService {
    private final AgentExecutionService executions;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public AgentResumeService(AgentExecutionService executions, ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.executions = Objects.requireNonNull(executions, "agent execution service must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    // ponytail: one-process resume lock; use a database lease before running multiple servers.
    public synchronized AgentExecutionService.AgentRunDetails resume(String ownerId, UUID runId) {
        AgentExecutionService.AgentRunDetails details = executions.details(ownerId, runId);
        if (details.run().status() == AgentRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalArgumentException(
                    "agent run is waiting for confirmation in its original conversation");
        }
        if (!details.run().status().terminal()) {
            throw new IllegalArgumentException("agent run is still active");
        }
        List<AgentExecutionStep> failed = details.steps().stream()
                .filter(step -> step.status() == AgentStepStatus.FAILED)
                .toList();
        if (failed.isEmpty()) throw new IllegalArgumentException("agent run has no failed tool steps to resume");

        for (AgentExecutionStep step : failed) {
            // A later action may depend on this result. Never continue past a blocked step.
            if (!replay(details.run(), step)) return executions.details(ownerId, runId);
        }
        AgentExecutionService.AgentRunDetails updated = executions.details(ownerId, runId);
        if (updated.steps().stream().noneMatch(step -> step.status() == AgentStepStatus.WAITING_CONFIRMATION)) {
            executions.markResumed(runId, "resumed persisted failed tool steps");
        }
        return executions.details(ownerId, runId);
    }

    private boolean replay(AgentRun run, AgentExecutionStep failed) {
        Map<String, Object> arguments = arguments(failed.argumentsJson());
        executions.beginStep(run.id(), failed.toolCallId(), failed.toolName(), arguments);
        ToolResult result;
        try (var scope = new com.minikun.tools.BackgroundToolScope(run.id(), true)) {
            result = toolExecutor.execute(
                    new ToolCallContext(new ConversationId(run.conversationId()), failed.toolCallId(), run.ownerId()),
                    new ToolCall(failed.toolCallId(), failed.toolName(), arguments));
        } catch (RuntimeException exception) {
            result = ToolResult.failure(com.minikun.tools.ToolErrorCode.EXECUTION_FAILED,
                    "resume failed; inspect the previous operation before retrying");
        }
        // Resume is an explicit retry already; never automatically repeat a possibly applied write.
        executions.finishStep(run.id(), failed.toolCallId(), result, false);
        if (!result.success()) executions.fail(run.id(), result.error());
        return result.success() && !(result.value() instanceof Map<?, ?> value
                && Boolean.parseBoolean(String.valueOf(value.get("requires_confirmation"))));
    }

    private Map<String, Object> arguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("stored agent tool arguments are invalid", exception);
        }
    }
}
