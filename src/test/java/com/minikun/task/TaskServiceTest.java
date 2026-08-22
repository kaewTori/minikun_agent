package com.minikun.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void createsOwnerScopedTaskAndTracksNextActionAndWaitingFor() {
        var store = new InMemoryTaskStore();
        var service = new TaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        PersonalTask task = service.create("owner-a", "conversation-a", "TASK", "แก้ integration test",
                "ทำให้ TinyGrad test รันใน CI", "2026-08-20T09:00:00+07:00", "Asia/Bangkok",
                "แยก socket test ออกจาก unit test", "รอ CI environment", "2026-08-19T10:00:00+07:00", "");

        assertEquals("owner-a", task.ownerId());
        assertEquals(TaskStatus.OPEN, task.status());
        assertEquals("แยก socket test ออกจาก unit test", task.nextAction());
        assertEquals(1, service.list("owner-a", null).size());
        assertEquals(0, service.list("owner-b", null).size());
    }

    @Test
    void updateAndCompletePreserveOwnerBoundary() {
        var store = new InMemoryTaskStore();
        var service = new TaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        PersonalTask task = service.create("owner-a", "conversation-a", "GOAL", "ส่งโปรเจกต์", "", "", "",
                "เปิด PR", "", "", "");

        PersonalTask blocked = service.update("owner-a", task.id(),
                new TaskPatch(null, null, TaskStatus.BLOCKED, null, null, "ตาม reviewer", "reviewer", null));
        PersonalTask done = service.complete("owner-a", task.id());

        assertEquals(TaskStatus.BLOCKED, blocked.status());
        assertEquals("reviewer", blocked.waitingFor());
        assertEquals(TaskStatus.DONE, done.status());
        assertTrue(done.completedAt() != null);
    }

    @Test
    void linksTaskToExplicitGoal() {
        var store = new InMemoryTaskStore();
        var service = new TaskService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID goalId = UUID.randomUUID();

        PersonalTask task = service.create("owner-a", "conversation-a", "TASK", "เดิน", "", "", "",
                "เดิน 30 นาที", "", "", "", goalId.toString());

        assertEquals(goalId, task.goalId());
        assertEquals(goalId.toString(), service.describe(task).get("goal_id"));
    }

    private static final class InMemoryTaskStore implements TaskStore {
        private final List<PersonalTask> tasks = new ArrayList<>();

        @Override public PersonalTask create(PersonalTask task) { tasks.add(task); return task; }
        @Override public Optional<PersonalTask> find(UUID id, String ownerId) {
            return tasks.stream().filter(task -> task.id().equals(id) && task.ownerId().equals(ownerId)).findFirst();
        }
        @Override public List<PersonalTask> list(String ownerId, TaskStatus status) {
            return tasks.stream().filter(task -> task.ownerId().equals(ownerId))
                    .filter(task -> status == null || task.status() == status).toList();
        }
        @Override public List<PersonalTask> findDueFollowUps(Instant now) {
            return tasks.stream().filter(PersonalTask::active)
                    .filter(task -> task.followUpAt() != null && !task.followUpAt().isAfter(now)).toList();
        }
        @Override public PersonalTask update(PersonalTask task) {
            tasks.replaceAll(current -> current.id().equals(task.id()) ? task : current);
            return task;
        }
        @Override public boolean delete(UUID id, String ownerId, Instant updatedAt) {
            return find(id, ownerId).map(task -> {
                update(new PersonalTask(task.id(), task.ownerId(), task.conversationId(), task.kind(), task.title(),
                        task.description(), TaskStatus.CANCELLED, task.parentId(), task.dueAt(), task.timezone(),
                        task.nextAction(), task.waitingFor(), task.followUpAt(), task.lastFollowUpAt(),
                        task.createdAt(), updatedAt, null));
                return true;
            }).orElse(false);
        }
        @Override public void markFollowedUp(PersonalTask task, Instant now) { }
    }
}
