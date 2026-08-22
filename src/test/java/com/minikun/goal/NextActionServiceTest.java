package com.minikun.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;
import com.minikun.task.TaskStore;

class NextActionServiceTest {
    @Test
    void recommendsLinkedTaskNextAction() {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        InMemoryGoalStore goalStore = new InMemoryGoalStore();
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        GoalService goals = new GoalService(goalStore, clock, taskStore);
        TaskService tasks = new TaskService(taskStore, clock);
        PersonalGoal goal = goals.create("default", "home", "สุขภาพ", "", "วัน", 0, 7, 0, null, "Asia/Bangkok");
        tasks.create("default", "home", "TASK", "เดิน", "", "", "Asia/Bangkok", "เดิน 30 นาที", "", "", "", goal.id().toString());

        List<NextActionRecommendation> result = new NextActionService(goals, tasks, clock).recommend("default", 5);

        assertEquals(1, result.size());
        assertEquals("เดิน 30 นาที", result.get(0).action());
        assertEquals(goal.id(), result.get(0).goalId());
    }

    @Test
    void recommendsCreatingFirstActionWhenGoalHasNoTasks() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
        InMemoryGoalStore goalsStore = new InMemoryGoalStore();
        InMemoryTaskStore tasksStore = new InMemoryTaskStore();
        GoalService goals = new GoalService(goalsStore, clock, tasksStore);
        PersonalGoal goal = goals.create("default", "home", "เรียนภาษา", "", "บท", 0, 10, 0, null, "Asia/Bangkok");

        NextActionRecommendation result = new NextActionService(goals, new TaskService(tasksStore, clock), clock)
                .recommend("default", 5).get(0);

        assertTrue(result.taskId() == null);
        assertTrue(result.action().contains("เรียนภาษา"));
    }

    private static final class InMemoryGoalStore implements GoalStore {
        private final List<PersonalGoal> values = new ArrayList<>();
        public PersonalGoal create(PersonalGoal value) { values.add(value); return value; }
        public Optional<PersonalGoal> find(UUID id, String owner) { return values.stream().filter(v -> v.id().equals(id) && v.ownerId().equals(owner)).findFirst(); }
        public List<PersonalGoal> list(String owner, GoalStatus status) { return values.stream().filter(v -> v.ownerId().equals(owner) && (status == null || v.status() == status)).toList(); }
        public PersonalGoal update(PersonalGoal value) { values.replaceAll(v -> v.id().equals(value.id()) ? value : v); return value; }
    }

    private static final class InMemoryTaskStore implements TaskStore {
        private final List<PersonalTask> values = new ArrayList<>();
        public PersonalTask create(PersonalTask value) { values.add(value); return value; }
        public Optional<PersonalTask> find(UUID id, String owner) { return values.stream().filter(v -> v.id().equals(id) && v.ownerId().equals(owner)).findFirst(); }
        public List<PersonalTask> list(String owner, TaskStatus status) { return values.stream().filter(v -> v.ownerId().equals(owner) && (status == null || v.status() == status)).sorted(Comparator.comparing(PersonalTask::createdAt)).toList(); }
        public List<PersonalTask> findDueFollowUps(Instant now) { return List.of(); }
        public PersonalTask update(PersonalTask value) { values.replaceAll(v -> v.id().equals(value.id()) ? value : v); return value; }
        public boolean delete(UUID id, String owner, Instant updatedAt) { return false; }
        public void markFollowedUp(PersonalTask task, Instant now) { }
    }
}
