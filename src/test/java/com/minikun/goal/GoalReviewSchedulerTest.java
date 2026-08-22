package com.minikun.goal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;

class GoalReviewSchedulerTest {
    @Test
    void formatsReviewMessageWithCurrentProgress() {
        GoalReviewScheduler scheduler = new GoalReviewScheduler(mock(GoalService.class),
                mock(NotificationDispatcher.class), mock(NotificationSchedulerMonitor.class), mock(JdbcTemplate.class),
                mock(ProactiveNotificationPolicy.class), java.time.Clock.systemUTC(), "default");
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        PersonalGoal goal = new PersonalGoal(java.util.UUID.randomUUID(), "default", "home", "สุขภาพ", "", GoalStatus.ACTIVE,
                40, "กิโล", 4, 10, now, ZoneId.of("Asia/Bangkok"), now, now, null);

        String message = scheduler.message(goal);

        assertTrue(message.contains("สุขภาพ"));
        assertTrue(message.contains("40%"));
    }

    @Test
    void releasesClaimWhenDeliveryFailsSoNextPollCanRetry() {
        GoalService goals = mock(GoalService.class);
        NotificationDispatcher notifications = mock(NotificationDispatcher.class);
        NotificationSchedulerMonitor monitor = mock(NotificationSchedulerMonitor.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProactiveNotificationPolicy policy = mock(ProactiveNotificationPolicy.class);
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        PersonalGoal goal = new PersonalGoal(java.util.UUID.randomUUID(), "default", "home", "สุขภาพ", "",
                GoalStatus.ACTIVE, 40, "กิโล", 4, 10, now, ZoneId.of("Asia/Bangkok"), now, now, null);
        when(policy.allows(now)).thenReturn(true);
        when(goals.dueForReview("default", now, 8)).thenReturn(List.of(goal));
        when(jdbc.update(contains("INSERT INTO minikun_goal_review_notification"),
                eq(goal.id()), any(Timestamp.class), any(Timestamp.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("transport unavailable"))
                .when(notifications).publish(any());
        GoalReviewScheduler scheduler = new GoalReviewScheduler(goals, notifications, monitor, jdbc,
                policy, Clock.fixed(now, java.time.ZoneOffset.UTC), "default");

        scheduler.reviewDueGoals();

        verify(jdbc).update(contains("DELETE FROM minikun_goal_review_notification"),
                eq(goal.id()), any(Timestamp.class));
        verify(monitor).failed("goal-review", now);
    }
}
