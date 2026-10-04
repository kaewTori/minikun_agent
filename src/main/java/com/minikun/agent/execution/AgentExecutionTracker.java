package com.minikun.agent.execution;

import com.minikun.tools.ToolResult;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionTracker {
    Optional<AgentRun> start(String ownerId, String conversationId, AgentPlanDraft plan);
    default Optional<AgentRun> start(String ownerId, String conversationId, String responseId, AgentPlanDraft plan) {
        return start(ownerId, conversationId, plan);
    }
    AgentExecutionStep beginStep(UUID runId, String toolCallId, String toolName, Map<String, Object> arguments);
    void finishStep(UUID runId, String toolCallId, ToolResult result, boolean willRetry);
    boolean shouldRetry(ToolResult result, int attempts);
    void waitingConfirmation(UUID runId, String summary);
    void complete(UUID runId, String summary);
    void limitReached(UUID runId, String reason);
    void fail(UUID runId, String reason);
    default Optional<String> completionNotice(UUID runId) { return Optional.empty(); }

    static AgentExecutionTracker noop() {
        return NoOpAgentExecutionTracker.INSTANCE;
    }

    final class NoOpAgentExecutionTracker implements AgentExecutionTracker {
        private static final NoOpAgentExecutionTracker INSTANCE = new NoOpAgentExecutionTracker();
        private NoOpAgentExecutionTracker() {}
        @Override public Optional<AgentRun> start(String ownerId, String conversationId, AgentPlanDraft plan) {
            return Optional.empty();
        }
        @Override public AgentExecutionStep beginStep(UUID runId, String callId, String tool,
                Map<String, Object> arguments) { return null; }
        @Override public void finishStep(UUID runId, String callId, ToolResult result, boolean willRetry) {}
        @Override public boolean shouldRetry(ToolResult result, int attempts) { return false; }
        @Override public void waitingConfirmation(UUID runId, String summary) {}
        @Override public void complete(UUID runId, String summary) {}
        @Override public void limitReached(UUID runId, String reason) {}
        @Override public void fail(UUID runId, String reason) {}
    }
}
