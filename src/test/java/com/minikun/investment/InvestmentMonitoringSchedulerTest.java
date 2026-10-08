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
        when(monitoring.replySymbol(any())).thenReturn("SCHD");
        NotificationDispatcher notifications = mock(NotificationDispatcher.class);
        NotificationSchedulerMonitor monitor = mock(NotificationSchedulerMonitor.class);
        ProactiveNotificationPolicy policy = new ProactiveNotificationPolicy(
                true, ZoneId.of("UTC"), LocalTime.of(22, 0), LocalTime.of(7, 0));
        InvestmentMonitoringScheduler scheduler = new InvestmentMonitoringScheduler(
                monitoring, notifications, monitor, policy,
                Clock.fixed(Instant.parse("2026-09-11T09:00:00Z"), ZoneOffset.UTC),
                "owner-a", "UTC", "08:15", "https://mini-kun:8443");

        scheduler.deliverDailyBrief();

        org.mockito.ArgumentCaptor<com.minikun.notification.NotificationRequest> request =
                org.mockito.ArgumentCaptor.forClass(com.minikun.notification.NotificationRequest.class);
        verify(notifications).publish(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals("https://mini-kun:8443/cockpit/?view=chat&reply_symbol=SCHD",
                request.getValue().clickUrl());
        verify(monitoring).markDelivered("owner-a", java.time.LocalDate.of(2026, 9, 11));
    }
}
