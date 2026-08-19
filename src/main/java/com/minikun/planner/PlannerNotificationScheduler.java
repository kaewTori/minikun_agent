package com.minikun.planner;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;

/** Delivers persisted reminders and advances recurring entries after delivery. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class PlannerNotificationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlannerNotificationScheduler.class);
    private static final String SCHEDULER = "planner";

    private final PlannerService planner;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final java.time.Clock clock;

    public PlannerNotificationScheduler(
            PlannerService planner,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            java.time.Clock clock) {
        this.planner = Objects.requireNonNull(planner, "planner service must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification service must not be null");
        this.monitor = Objects.requireNonNull(monitor, "scheduler monitor must not be null");
        this.clock = Objects.requireNonNull(clock, "planner clock must not be null");
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.planner.poll-interval-ms:30000}")
    public void deliverDueReminders() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        for (PlannerEvent event : planner.due(now)) {
            try {
                notifications.publish(new NotificationRequest(
                        "PLANNER", event.id().toString(), NotificationChannel.REMINDER,
                        "Mini-kun reminder", message(event, now), 3, "bell,calendar"));
                planner.markDelivered(event, now);
                monitor.delivered(SCHEDULER, clock.instant());
                LOGGER.info("process=planner event=delivered id={} recurrence={}",
                        event.id(), event.recurrence());
            } catch (RuntimeException exception) {
                monitor.failed(SCHEDULER, clock.instant());
                LOGGER.warn("process=planner event=delivery_failed id={} reason={}",
                        event.id(), exception.getMessage());
            }
        }
    }

    private String message(PlannerEvent event, Instant now) {
        ZonedDateTime start = event.startsAt().atZone(event.timezone());
        Duration untilStart = Duration.between(now, event.startsAt());
        String timing;
        if (untilStart.toMinutes() > 0) {
            timing = "อีกประมาณ " + untilStart.toMinutes() + " นาที";
        } else {
            timing = "ถึงเวลาแล้ว";
        }
        StringBuilder message = new StringBuilder("พี่สาวครับ ").append(timing)
                .append("นะครับ: ").append(event.title())
                .append("\nเวลา: ").append(start.toLocalDate()).append(" ").append(start.toLocalTime())
                .append(" (จุดเวลา ").append(event.timezone().getId()).append(")");
        if (!event.note().isBlank()) {
            message.append("\nรายละเอียด: ").append(event.note());
        }
        return message.toString();
    }
}
