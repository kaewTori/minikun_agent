package com.minikun.calendar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationSchedulerMonitor;

class ExternalCalendarReminderSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");

    @Test
    void sendsTimedOccurrenceOnceAndSkipsAllDayEvent() {
        ExternalCalendarService calendar = mock(ExternalCalendarService.class);
        NotificationDispatcher notifications = mock(NotificationDispatcher.class);
        NotificationSchedulerMonitor monitor = mock(NotificationSchedulerMonitor.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExternalCalendarEvent meeting = new ExternalCalendarEvent("meeting", "Planning", "", "Room A",
                NOW.plusSeconds(10 * 60), NOW.plusSeconds(40 * 60), false, "personal");
        ExternalCalendarEvent allDay = new ExternalCalendarEvent("holiday", "Holiday", "", "",
                NOW, NOW.plus(Duration.ofDays(1)), true, "personal");
        when(calendar.events(any(), any())).thenReturn(List.of(meeting, allDay));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        ExternalCalendarReminderScheduler scheduler = new ExternalCalendarReminderScheduler(
                calendar, notifications, monitor, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15), "Asia/Bangkok");

        scheduler.deliverReminders();

        verify(notifications).publish(any());
        verify(jdbc).update(anyString(), eq("meeting"), any(), any());
        verify(jdbc, never()).update(anyString(), eq("holiday"), any(), any());
    }
}
