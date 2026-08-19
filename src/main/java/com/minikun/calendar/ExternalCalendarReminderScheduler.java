package com.minikun.calendar;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;

/** Sends one reminder for each timed external-calendar occurrence. */
@Component
@ConditionalOnProperty(
        name = {"minikun.calendar.external.enabled", "minikun.calendar.external.reminders.enabled"},
        havingValue = "true")
public final class ExternalCalendarReminderScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalCalendarReminderScheduler.class);
    private static final String SCHEDULER = "external-calendar";

    private final ExternalCalendarService calendar;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration remindBefore;
    private final ZoneId zone;

    public ExternalCalendarReminderScheduler(
            ExternalCalendarService calendar,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${minikun.calendar.external.reminders.before:15m}") Duration remindBefore,
            @Value("${minikun.calendar.external.zone:Asia/Bangkok}") String zone) {
        this.calendar = Objects.requireNonNull(calendar, "external calendar must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification dispatcher must not be null");
        this.monitor = Objects.requireNonNull(monitor, "scheduler monitor must not be null");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.clock = Objects.requireNonNull(clock, "calendar clock must not be null");
        if (remindBefore == null || remindBefore.isNegative() || remindBefore.isZero()
                || remindBefore.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("external calendar reminder lead time must be between 1 second and 7 days");
        }
        this.remindBefore = remindBefore;
        this.zone = ZoneId.of(zone);
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.calendar.external.reminders.poll-interval-ms:60000}")
    public void deliverReminders() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        try {
            List<ExternalCalendarEvent> upcoming = calendar.events(
                    now.minus(Duration.ofMinutes(2)), now.plus(remindBefore));
            for (ExternalCalendarEvent event : upcoming) {
                if (event.allDay() || event.startsAt().isBefore(now.minus(Duration.ofMinutes(2))) || sent(event)) continue;
                notifications.publish(new NotificationRequest(
                        "EXTERNAL_CALENDAR", event.occurrenceId(), NotificationChannel.REMINDER,
                        "Mini-kun calendar reminder", message(event), 4, "calendar,alarm_clock"));
                markSent(event, clock.instant());
                monitor.delivered(SCHEDULER, clock.instant());
                LOGGER.info("process=external_calendar event=reminder_delivered uid_hash={} starts_at={}",
                        Integer.toHexString(event.uid().hashCode()), event.startsAt());
            }
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOGGER.warn("process=external_calendar event=poll_failed reason={}", exception.getMessage());
        }
    }

    private String message(ExternalCalendarEvent event) {
        StringBuilder result = new StringBuilder("อีก ").append(remindBefore.toMinutes())
                .append(" นาทีจะถึง ").append(event.title())
                .append(" เวลา ").append(DateTimeFormatter.ofPattern("HH:mm").format(event.startsAt().atZone(zone)));
        if (!event.location().isBlank()) result.append("\nสถานที่: ").append(event.location());
        return result.toString();
    }

    private boolean sent(ExternalCalendarEvent event) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM minikun_external_calendar_notification
                WHERE event_uid = ? AND occurrence_start = ?
                """, Integer.class, event.uid(), Timestamp.from(event.startsAt()));
        return count != null && count > 0;
    }

    private void markSent(ExternalCalendarEvent event, Instant sentAt) {
        jdbc.update("""
                INSERT INTO minikun_external_calendar_notification (event_uid, occurrence_start, notified_at)
                VALUES (?, ?, ?) ON CONFLICT (event_uid, occurrence_start) DO NOTHING
                """, event.uid(), Timestamp.from(event.startsAt()), Timestamp.from(sentAt));
    }
}
