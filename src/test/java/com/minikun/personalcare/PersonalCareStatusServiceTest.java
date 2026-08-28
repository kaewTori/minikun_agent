package com.minikun.personalcare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.goal.GoalService;
import com.minikun.goal.GoalStatus;
import com.minikun.goal.PersonalGoal;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalCareStatusServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T03:00:00Z");

    @Test
    void doesNotCountGoalCompletedDuringProgressSynchronizationAsOpen() {
        TaskService tasks = mock(TaskService.class);
        GoalService goals = mock(GoalService.class);
        when(tasks.list("owner", null)).thenReturn(List.of());
        when(goals.syncOpenProgress("owner", List.of())).thenReturn(List.of(completedGoal()));
        PersonalCareStatusService service = new PersonalCareStatusService(
                tasks, goals, null, Clock.fixed(NOW, ZoneOffset.UTC));

        var snapshot = service.snapshot("owner");

        assertEquals(0, snapshot.get("open_tasks"));
        assertEquals(0, snapshot.get("open_goals"));
        assertEquals(false, snapshot.get("attention_required"));
    }

    private PersonalGoal completedGoal() {
        return new PersonalGoal(UUID.randomUUID(), "owner", "conversation", "Completed", "",
                GoalStatus.COMPLETED, 100, "tasks", 1, 1, null, ZoneId.of("Asia/Bangkok"),
                NOW.minusSeconds(60), NOW, NOW);
    }
}
