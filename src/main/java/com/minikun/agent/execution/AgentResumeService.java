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

/** Replays persisted failed tool steps without bypassing the tools' confirmation policies. */
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

    public AgentExecutionService.AgentRunDetails resume(String ownerId, UUID runId) {
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

        for (AgentExecutionStep step : failed) replay(details.run(), step);
        AgentExecutionService.AgentRunDetails updated = executions.details(ownerId, runId);
        if (updated.steps().stream().noneMatch(step -> step.status() == AgentStepStatus.WAITING_CONFIRMATION)) {
            executions.markResumed(runId, "resumed persisted failed tool steps");
        }
        return executions.details(ownerId, runId);
    }

    private void replay(AgentRun run, AgentExecutionStep failed) {
        Map<String, Object> arguments = arguments(failed.argumentsJson());
        AgentExecutionStep attempt = executions.beginStep(run.id(), failed.toolCallId(), failed.toolName(), arguments);
        while (true) {
            ToolResult result = toolExecutor.execute(
                    new ToolCallContext(new ConversationId(run.conversationId()), failed.toolCallId(), run.ownerId()),
                    new ToolCall(failed.toolCallId(), failed.toolName(), arguments));
            boolean retry = executions.shouldRetry(result, attempt.attempts());
            executions.finishStep(run.id(), failed.toolCallId(), result, retry);
            if (!retry) return;
            attempt = executions.beginStep(run.id(), failed.toolCallId(), failed.toolName(), arguments);
        }
    }

    private Map<String, Object> arguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("stored agent tool arguments are invalid", exception);
        }
    }
}
