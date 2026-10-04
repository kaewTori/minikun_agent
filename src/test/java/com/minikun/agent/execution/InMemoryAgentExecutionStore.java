package com.minikun.agent.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class InMemoryAgentExecutionStore implements AgentExecutionStore {
    private final Map<UUID, AgentRun> runs = new LinkedHashMap<>();
    private final Map<UUID, AgentExecutionStep> steps = new LinkedHashMap<>();

    @Override
    public AgentRun createRun(AgentRun run) {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public AgentRun updateRun(AgentRun run) {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public Optional<AgentRun> findRun(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public Optional<AgentRun> findRun(UUID id, String ownerId) {
        return findRun(id).filter(run -> run.ownerId().equals(ownerId));
    }

    @Override
    public List<AgentRun> listRuns(String ownerId, AgentRunStatus status, int limit) {
        return runs.values().stream()
                .filter(run -> run.ownerId().equals(ownerId))
                .filter(run -> status == null || run.status() == status)
                .sorted(Comparator.comparing(AgentRun::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<AgentRun> listRuns(String ownerId, String conversationId, AgentRunStatus status, int limit) {
        return listRuns(ownerId, status, Integer.MAX_VALUE).stream()
                .filter(run -> run.conversationId().equals(conversationId)).limit(limit).toList();
    }

    @Override
    public AgentExecutionStep createStep(AgentExecutionStep step) {
        steps.put(step.id(), step);
        return step;
    }

    @Override
    public AgentExecutionStep updateStep(AgentExecutionStep step) {
        steps.put(step.id(), step);
        return step;
    }

    @Override
    public Optional<AgentExecutionStep> findStep(UUID runId, String toolCallId) {
        return steps.values().stream()
                .filter(step -> step.runId().equals(runId) && step.toolCallId().equals(toolCallId))
                .findFirst();
    }

    @Override
    public List<AgentExecutionStep> listSteps(UUID runId) {
        List<AgentExecutionStep> result = new ArrayList<>(steps.values().stream()
                .filter(step -> step.runId().equals(runId))
                .toList());
        result.sort(Comparator.comparingInt(AgentExecutionStep::stepIndex));
        return List.copyOf(result);
    }
}
