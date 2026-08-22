package com.minikun.goal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.ZoneId;
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
}
