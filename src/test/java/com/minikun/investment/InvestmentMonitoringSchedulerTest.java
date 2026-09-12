package com.minikun.investment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvestmentMonitoringSchedulerTest {
    @Test
    void deliversOnceAndMarksTheBriefOnlyAfterNotification() {
        InvestmentMonitoringService monitoring = mock(InvestmentMonitoringService.class);
        when(monitoring.prepareDaily("owner-a")).thenReturn(Optional.of(Map.of("report_date", "2026-09-11")));
        when(monitoring.formatBrief(any())).thenReturn("brief");
        NotificationDispatcher notifications = mock(NotificationDispatcher.class);
        NotificationSchedulerMonitor monitor = mock(NotificationSchedulerMonitor.class);
        ProactiveNotificationPolicy policy = new ProactiveNotificationPolicy(
                true, ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));
        InvestmentMonitoringScheduler scheduler = new InvestmentMonitoringScheduler(
                monitoring, notifications, monitor, policy,
                Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC),
                "owner-a", "UTC", "08:15");

        scheduler.deliverDailyBrief();

        verify(notifications).publish(any());
        verify(monitoring).markDelivered("owner-a", java.time.LocalDate.of(2026, 9, 11));
    }
}
