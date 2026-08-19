package com.minikun.task;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends at-most-once follow-up notifications for still-open work. */
@Component
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TaskService.class)
public final class TaskFollowUpScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskFollowUpScheduler.class);
    private static final String SCHEDULER = "task-follow-up";

    private final TaskService tasks;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final ProactiveNotificationPolicy policy;
    private final Clock clock;

    public TaskFollowUpScheduler(TaskService tasks, NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor, ProactiveNotificationPolicy policy, Clock clock) {
        this.tasks = Objects.requireNonNull(tasks);
        this.notifications = Objects.requireNonNull(notifications);
        this.monitor = Objects.requireNonNull(monitor);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.task.poll-interval-ms:30000}")
    public void deliverDueFollowUps() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        if (!policy.allows(now)) {
            return;
        }
        for (PersonalTask task : tasks.dueFollowUps(now)) {
            try {
                notifications.publish(new NotificationRequest(
                        "TASK", task.id().toString(), NotificationChannel.REMINDER,
                        "Mini-kun task follow-up", message(task), 3, "bell,checklist"));
                tasks.markFollowedUp(task, now);
                monitor.delivered(SCHEDULER, clock.instant());
            } catch (RuntimeException exception) {
                // Keep the task due so a later poll can retry delivery.
                monitor.failed(SCHEDULER, clock.instant());
                LOGGER.warn("process=task_follow_up event=delivery_failed id={} reason={}",
                        task.id(), exception.getMessage());
            }
        }
    }

    private String message(PersonalTask task) {
        StringBuilder message = new StringBuilder("พี่สาวครับ มินิคุงขอตามงานนี้นะครับ: ")
                .append(task.title());
        if (!task.nextAction().isBlank()) message.append("\nขั้นตอนถัดไป: ").append(task.nextAction());
        if (!task.waitingFor().isBlank()) message.append("\nกำลังรอ: ").append(task.waitingFor());
        if (!task.description().isBlank()) message.append("\nรายละเอียด: ").append(task.description());
        return message.toString();
    }
}
