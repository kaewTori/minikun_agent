package com.minikun.goal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;

/** Sends one bounded reminder when a personal goal reaches its review date. */
@Component
@ConditionalOnBean(GoalService.class)
@ConditionalOnProperty(name = "minikun.goal.review.enabled", havingValue = "true", matchIfMissing = true)
public final class GoalReviewScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(GoalReviewScheduler.class);
    private static final String SCHEDULER = "goal-review";
    private final GoalService goals;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final JdbcTemplate jdbc;
    private final ProactiveNotificationPolicy policy;
    private final Clock clock;
    private final String ownerId;

    public GoalReviewScheduler(GoalService goals, NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor, JdbcTemplate jdbc, ProactiveNotificationPolicy policy,
            Clock clock, @Value("${minikun.goal.review.owner-id:default}") String ownerId) {
        this.goals = Objects.requireNonNull(goals);
        this.notifications = Objects.requireNonNull(notifications);
        this.monitor = Objects.requireNonNull(monitor);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.ownerId = Objects.requireNonNullElse(ownerId, "default").trim();
        if (this.ownerId.isBlank() || "*".equals(this.ownerId)) throw new IllegalArgumentException("goal review owner is invalid");
        monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.goal.review.poll-interval-ms:30000}")
    public void reviewDueGoals() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        if (!policy.allows(now)) return;
        try {
            List<PersonalGoal> due = goals.dueForReview(ownerId, now).stream().limit(8).toList();
            for (PersonalGoal goal : due) {
                if (!claim(goal, now)) continue;
                notifications.publish(new NotificationRequest("GOAL_REVIEW", goal.id().toString(),
                        NotificationChannel.REMINDER, "Mini-kun goal review",
                        message(goal), 3, "target,chart_with_upwards_trend"));
                monitor.delivered(SCHEDULER, clock.instant());
            }
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOG.warn("process=goal_review event=failed owner_id={} reason={}", ownerId, exception.getMessage());
        }
    }

    String message(PersonalGoal goal) {
        return "พี่สาวครับ ถึงเวลาทบทวนเป้าหมายแล้วนะครับ\n\n"
                + "เป้าหมาย: " + goal.title() + "\n"
                + "ความคืบหน้า: " + goal.progressPercent() + "%\n"
                + "ลองทบทวนว่าควรทำขั้นตอนถัดไปอะไร หรือปรับเป้าหมายให้เหมาะกับสถานการณ์ตอนนี้ไหมครับ";
    }

    private boolean claim(PersonalGoal goal, Instant now) {
        return jdbc.update("""
                INSERT INTO minikun_goal_review_notification (goal_id, review_at, notified_at)
                VALUES (?, ?, ?) ON CONFLICT (goal_id, review_at) DO NOTHING
                """, goal.id(), Timestamp.from(goal.nextReviewAt()), Timestamp.from(now)) > 0;
    }
}
