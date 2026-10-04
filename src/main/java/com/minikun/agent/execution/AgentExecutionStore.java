package com.minikun.agent.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionStore {
    AgentRun createRun(AgentRun run);
    AgentRun updateRun(AgentRun run);
    Optional<AgentRun> findRun(UUID id);
    Optional<AgentRun> findRun(UUID id, String ownerId);
    List<AgentRun> listRuns(String ownerId, AgentRunStatus status, int limit);
    List<AgentRun> listRuns(String ownerId, String conversationId, AgentRunStatus status, int limit);
    AgentExecutionStep createStep(AgentExecutionStep step);
    AgentExecutionStep updateStep(AgentExecutionStep step);
    Optional<AgentExecutionStep> findStep(UUID runId, String toolCallId);
    List<AgentExecutionStep> listSteps(UUID runId);
}
