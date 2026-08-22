package com.minikun.goal;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;

/** Selects the smallest useful next step from the user's current goals and tasks. */
@Service
@ConditionalOnBean({GoalService.class, TaskService.class})
public final class NextActionService {
    private final GoalService goals;
    private final TaskService tasks;
    private final Clock clock;

    public NextActionService(GoalService goals, TaskService tasks, Clock clock) {
        this.goals = Objects.requireNonNull(goals);
        this.tasks = Objects.requireNonNull(tasks);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<NextActionRecommendation> recommend(String ownerId, int limit) {
        int maximum = Math.max(1, Math.min(limit, 20));
        Instant now = clock.instant();
        List<PersonalTask> ownerTasks = tasks.list(ownerId, null);
        return goals.syncOpenProgress(ownerId).stream()
                .filter(PersonalGoal::open)
                .flatMap(goal -> recommendation(goal, ownerTasks, now).stream())
                .sorted(Comparator.comparingInt(NextActionRecommendation::priority).reversed())
                .limit(maximum)
                .toList();
    }

    private List<NextActionRecommendation> recommendation(PersonalGoal goal, List<PersonalTask> allTasks, Instant now) {
        List<PersonalTask> linked = allTasks.stream()
                .filter(task -> goal.id().equals(task.goalId()) && task.active())
                .sorted(Comparator.comparing(PersonalTask::dueAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        PersonalTask selected = linked.stream().filter(task -> task.status() == TaskStatus.BLOCKED).findFirst()
                .orElseGet(() -> linked.stream().filter(task -> task.dueAt() != null && !task.dueAt().isAfter(now))
                        .findFirst().orElseGet(() -> linked.stream().findFirst().orElse(null)));
        if (selected == null) {
            return List.of(new NextActionRecommendation(goal.id(), goal.title(), null,
                    "กำหนด next action แรกให้เป้าหมาย: " + goal.title(),
                    "เป้าหมายยังไม่มี task ที่เชื่อมอยู่", 3));
        }
        if (selected.status() == TaskStatus.BLOCKED) {
            String waiting = selected.waitingFor().isBlank() ? "ตรวจสอบสิ่งที่ขวางอยู่" : "ติดตาม " + selected.waitingFor();
            return List.of(new NextActionRecommendation(goal.id(), goal.title(), selected.id(), waiting,
                    "task ถูก block", 5));
        }
        String action = selected.nextAction().isBlank() ? selected.title() : selected.nextAction();
        int priority = selected.dueAt() != null && !selected.dueAt().isAfter(now) ? 5 : 4;
        return List.of(new NextActionRecommendation(goal.id(), goal.title(), selected.id(), action,
                priority == 5 ? "task ถึงกำหนดแล้ว" : "task ถัดไปของเป้าหมาย", priority));
    }
}
