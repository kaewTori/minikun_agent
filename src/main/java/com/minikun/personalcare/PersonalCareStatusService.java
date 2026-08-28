package com.minikun.personalcare;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.agent.execution.AgentRunStatus;
import com.minikun.goal.PersonalGoal;
import com.minikun.goal.GoalService;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;

/** Owner-scoped compact snapshot for daily care dashboards and agent self-awareness. */
@Service
@ConditionalOnProperty(name = {"minikun.task.enabled", "minikun.goal.enabled"},
        havingValue = "true", matchIfMissing = true)
public final class PersonalCareStatusService {
    private final TaskService tasks;
    private final GoalService goals;
    private final AgentExecutionService executions;
    private final Clock clock;

    public PersonalCareStatusService(TaskService tasks, GoalService goals,
            ObjectProvider<AgentExecutionService> executions, Clock clock) {
        this.tasks = Objects.requireNonNull(tasks);
        this.goals = Objects.requireNonNull(goals);
        this.executions = executions == null ? null : executions.getIfAvailable();
        this.clock = Objects.requireNonNull(clock);
    }

    public Map<String, Object> snapshot(String ownerId) {
        Instant now = clock.instant();
        var ownerTasks = tasks.list(ownerId, null);
        var openTasks = ownerTasks.stream().filter(PersonalTask::active).toList();
        var openGoals = goals.syncOpenProgress(ownerId, ownerTasks).stream()
                .filter(PersonalGoal::open)
                .toList();
        var dueTasks = openTasks.stream().filter(task -> task.dueAt() != null && !task.dueAt().isAfter(now)).count();
        var dueGoals = openGoals.stream()
                .filter(goal -> goal.nextReviewAt() != null && !goal.nextReviewAt().isAfter(now)).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("owner_id", ownerId);
        result.put("generated_at", now.toString());
        result.put("open_tasks", openTasks.size());
        result.put("due_tasks", dueTasks);
        result.put("open_goals", openGoals.size());
        result.put("goals_due_for_review", dueGoals);
        result.put("attention_required", dueTasks > 0 || dueGoals > 0);
        if (executions != null) {
            result.put("agent_runs", executions.list(ownerId, null, 20).stream()
                    .collect(java.util.stream.Collectors.groupingBy(run -> run.status().name(),
                            LinkedHashMap::new, java.util.stream.Collectors.counting())));
            result.put("agent_waiting_confirmation", executions.list(ownerId, AgentRunStatus.WAITING_CONFIRMATION, 20).size());
        }
        return result;
    }
}
