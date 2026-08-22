package com.minikun.personalloop;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty(name = "minikun.personal-loop.weekly-review.enabled", havingValue = "true", matchIfMissing = true)
public final class WeeklyReviewScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WeeklyReviewScheduler.class);
    private final WeeklyReviewService service;
    private final Clock clock;
    private final String ownerId;
    private final ZoneId zone;
    private final DayOfWeek day;
    private final LocalTime time;

    public WeeklyReviewScheduler(WeeklyReviewService service, Clock clock,
            @Value("${minikun.personal-loop.owner-id:default}") String ownerId,
            @Value("${minikun.personal-loop.timezone:Asia/Bangkok}") String zone,
            @Value("${minikun.personal-loop.weekly-review.day:SUNDAY}") String day,
            @Value("${minikun.personal-loop.weekly-review.time:19:00}") String time) {
        this.service = service; this.clock = clock; this.ownerId = ownerId; this.zone = ZoneId.of(zone);
        this.day = DayOfWeek.valueOf(day.toUpperCase()); this.time = LocalTime.parse(time);
    }

    @Scheduled(fixedDelay = 60000)
    public void generateIfDue() {
        var localNow = clock.instant().atZone(zone);
        if (localNow.getDayOfWeek() != day || localNow.toLocalTime().isBefore(time)) return;
        var end = localNow.toLocalDate().atTime(time).atZone(zone).toInstant();
        try { service.generate(ownerId, "weekly-review", end.minus(Duration.ofDays(7)), end); }
        catch (RuntimeException exception) { LOG.warn("process=weekly_review event=generation_failed reason={}", exception.getMessage()); }
    }
}
