package com.minikun.investment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;

/** Delivers one read-only investment/news brief after the configured local time. */
@Component
@ConditionalOnProperty(
        name = {"minikun.investment.enabled", "minikun.investment.monitor.enabled"},
        havingValue = "true", matchIfMissing = true)
public final class InvestmentMonitoringScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InvestmentMonitoringScheduler.class);
    private static final String SCHEDULER = "investment-monitor";

    private final InvestmentMonitoringService monitoring;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final ProactiveNotificationPolicy notificationPolicy;
    private final Clock clock;
    private final String ownerId;
    private final ZoneId zone;
    private final LocalTime sendAt;
    private final java.net.URI chatOrigin;

    public InvestmentMonitoringScheduler(
            InvestmentMonitoringService monitoring,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            ProactiveNotificationPolicy notificationPolicy,
            Clock clock,
            @Value("${minikun.investment.monitor.owner-id:default}") String ownerId,
            @Value("${minikun.investment.monitor.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.investment.monitor.time:08:15}") String sendAt,
            @Value("${minikun.sync.canonical-origin:https://mini-kun:8443}") String canonicalOrigin) {
        this.monitoring = Objects.requireNonNull(monitoring, "investment monitoring service must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification dispatcher must not be null");
        this.monitor = Objects.requireNonNull(monitor, "notification scheduler monitor must not be null");
        this.notificationPolicy = Objects.requireNonNull(notificationPolicy, "notification policy must not be null");
        this.clock = Objects.requireNonNull(clock, "investment scheduler clock must not be null");
        this.ownerId = InvestmentPolicy.requireOwner(ownerId);
        this.chatOrigin = java.net.URI.create(canonicalOrigin);
        if (!"https".equals(chatOrigin.getScheme()) || chatOrigin.getHost() == null
                || chatOrigin.getUserInfo() != null || chatOrigin.getQuery() != null || chatOrigin.getFragment() != null)
            throw new IllegalArgumentException("investment chat origin must be an HTTPS origin");
        try {
            this.zone = ZoneId.of(Objects.requireNonNullElse(zone, "Asia/Bangkok").trim());
            this.sendAt = LocalTime.parse(Objects.requireNonNullElse(sendAt, "08:15").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid investment monitor timezone or time", exception);
        }
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.investment.monitor.poll-interval-ms:30000}")
    public void deliverDailyBrief() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        if (!notificationPolicy.allows(now)) return;
        ZonedDateTime localNow = now.atZone(zone);
        if (localNow.toLocalTime().isBefore(sendAt)) return;
        var date = localNow.toLocalDate();
        try {
            var report = monitoring.prepareDaily(ownerId);
            if (report.isEmpty()) return;
            String replySymbol = monitoring.replySymbol(report.get());
            String clickUrl = replySymbol.isBlank() ? "" : chatOrigin.resolve(
                    "/cockpit/?view=chat&reply_symbol=" + java.net.URLEncoder.encode(replySymbol, java.nio.charset.StandardCharsets.UTF_8)).toString();
            notifications.publish(new NotificationRequest(
                    "INVESTMENT", ownerId + ":" + date, NotificationChannel.REMINDER,
                    "Mini-kun investment brief", monitoring.formatBrief(report.get()), 3,
                    "investment,market,news", clickUrl));
            monitoring.markDelivered(ownerId, date);
            monitor.delivered(SCHEDULER, clock.instant());
            LOGGER.info("process=investment_monitor event=delivered owner_id={} date={}", ownerId, date);
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOGGER.warn("process=investment_monitor event=delivery_failed owner_id={} reason={}",
                    ownerId, exception.getMessage());
        }
    }
}
