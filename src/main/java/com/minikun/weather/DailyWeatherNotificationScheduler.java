package com.minikun.weather;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.notification.NtfyNotificationService;
import com.minikun.proactive.ProactiveNotificationPolicy;

/** Sends one durable daily weather notification after the configured local time. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class DailyWeatherNotificationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyWeatherNotificationScheduler.class);
    private static final String STATE_KEY = "daily-weather";
    private static final String SCHEDULER = "daily-weather";

    private final WeatherProvider weatherProvider;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProactiveNotificationPolicy policy;
    private final boolean enabled;
    private final String location;
    private final String countryCode;
    private final ZoneId zone;
    private final LocalTime sendAt;

    public DailyWeatherNotificationScheduler(
            WeatherProvider weatherProvider,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            JdbcTemplate jdbc,
            Clock clock,
            ProactiveNotificationPolicy policy,
            @Value("${minikun.weather.alert.enabled:true}") boolean enabled,
            @Value("${minikun.weather.alert.location:Bangkok}") String location,
            @Value("${minikun.weather.alert.country-code:TH}") String countryCode,
            @Value("${minikun.weather.alert.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.weather.alert.time:07:00}") String sendAt) {
        this.weatherProvider = Objects.requireNonNull(weatherProvider, "weather provider must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification service must not be null");
        this.monitor = Objects.requireNonNull(monitor, "scheduler monitor must not be null");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.clock = Objects.requireNonNull(clock, "weather clock must not be null");
        this.policy = Objects.requireNonNull(policy, "proactive policy must not be null");
        this.enabled = enabled;
        this.location = Objects.requireNonNullElse(location, "Bangkok").trim();
        this.countryCode = Objects.requireNonNullElse(countryCode, "TH").trim();
        try {
            this.zone = ZoneId.of(zone);
            this.sendAt = LocalTime.parse(sendAt);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid daily weather alert timezone or time", exception);
        }
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.weather.alert.poll-interval-ms:30000}")
    public void deliverDailyWeather() {
        Instant pollTime = clock.instant();
        monitor.polled(SCHEDULER, pollTime);
        if (!enabled) {
            return;
        }
        if (!policy.allows(pollTime)) {
            return;
        }
        ZonedDateTime now = pollTime.atZone(zone);
        LocalDate today = now.toLocalDate();
        if (now.toLocalTime().isBefore(sendAt) || alreadySent(today)) {
            return;
        }
        try {
            WeatherReport report = weatherProvider.forecast(new WeatherRequest(location, "วันนี้", countryCode));
            notifications.publish(new NotificationRequest(
                    "WEATHER", today.toString(), NotificationChannel.WEATHER, "Mini-kun weather",
                    WeatherAlertFormatter.format(report), WeatherAlertFormatter.priority(report),
                    WeatherAlertFormatter.tags(report)));
            markSent(today, clock.instant());
            monitor.delivered(SCHEDULER, clock.instant());
            LOGGER.info("process=weather_alert event=delivered location={} date={} zone={}",
                    location, today, zone);
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOGGER.warn("process=weather_alert event=delivery_failed location={} reason={}",
                    location, exception.getMessage());
        }
    }

    private boolean alreadySent(LocalDate date) {
        List<LocalDate> dates = jdbc.query(
                "SELECT last_sent_date FROM minikun_weather_alert_state WHERE alert_key = ?",
                (resultSet, rowNum) -> {
                    java.sql.Date value = resultSet.getDate(1);
                    return value == null ? null : value.toLocalDate();
                }, STATE_KEY);
        return dates.stream().anyMatch(date::equals);
    }

    private void markSent(LocalDate date, Instant sentAt) {
        jdbc.update("""
                INSERT INTO minikun_weather_alert_state (alert_key, last_sent_date, last_sent_at)
                VALUES (?, ?, ?)
                ON CONFLICT (alert_key) DO UPDATE SET last_sent_date = EXCLUDED.last_sent_date,
                    last_sent_at = EXCLUDED.last_sent_at
                """, STATE_KEY, java.sql.Date.valueOf(date), Timestamp.from(sentAt));
    }
}
