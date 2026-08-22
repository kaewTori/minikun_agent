package com.minikun.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.minikun.task.TaskStore;

class GoalServiceTest {
    @Test
    void updatesProgressAndCompletesAtOneHundredPercent() {
        InMemoryGoalStore store = new InMemoryGoalStore();
        GoalService service = new GoalService(store, Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC));
        PersonalGoal goal = service.create("default", "conversation", "อ่านหนังสือ", "", "บท", 0, 10, 0, null, "Asia/Bangkok");

        PersonalGoal updated = service.updateProgress("default", goal.id(), 100, 10, null);

        assertEquals(GoalStatus.COMPLETED, updated.status());
        assertEquals(100, updated.progressPercent());
    }

    @Test
    void rejectsWildcardOwner() {
        GoalService service = new GoalService(new InMemoryGoalStore(), Clock.systemUTC());

        assertThrows(IllegalArgumentException.class,
                () -> service.list("*", null));
    }

    @Test
    void exposesBoundedActiveGoalSummaryForPlanning() {
        InMemoryGoalStore store = new InMemoryGoalStore();
        GoalService service = new GoalService(store, Clock.systemUTC());
        service.create("default", "conversation", "สุขภาพ", "รายละเอียดที่ไม่ควรส่งเข้า planner", "กิโล", 2, 10, 20, null, "Asia/Bangkok");

        String summary = service.activeSummary("default", 8, 400);

        assertEquals("- สุขภาพ: 20% (current 2.0 / target 10.0 กิโล)", summary);
    }

    @Test
    void loadsOwnerTasksOnceWhenSynchronizingMultipleGoals() {
        InMemoryGoalStore store = new InMemoryGoalStore();
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.list("default", null)).thenReturn(List.of());
        GoalService service = new GoalService(store, Clock.systemUTC(), tasks);
        service.create("default", "home", "เป้าหมายหนึ่ง", "", "", 0, 0, 0, null, "Asia/Bangkok");
        service.create("default", "home", "เป้าหมายสอง", "", "", 0, 0, 0, null, "Asia/Bangkok");

        assertEquals(2, service.syncOpenProgress("default").size());

        verify(tasks).list("default", null);
    }

    private static final class InMemoryGoalStore implements GoalStore {
        private final List<PersonalGoal> values = new ArrayList<>();
        public PersonalGoal create(PersonalGoal goal) { values.add(goal); return goal; }
        public Optional<PersonalGoal> find(UUID id, String ownerId) { return values.stream().filter(g -> g.id().equals(id) && g.ownerId().equals(ownerId)).findFirst(); }
        public List<PersonalGoal> list(String ownerId, GoalStatus status) { return values.stream().filter(g -> g.ownerId().equals(ownerId) && (status == null || g.status() == status)).toList(); }
        public PersonalGoal update(PersonalGoal goal) { values.replaceAll(value -> value.id().equals(goal.id()) ? goal : value); return goal; }
    }
}
