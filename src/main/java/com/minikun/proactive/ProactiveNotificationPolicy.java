package com.minikun.proactive;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Central safety policy for notifications initiated by Mini-kun. */
@Component
public final class ProactiveNotificationPolicy {
    private final boolean enabled;
    private final ZoneId zone;
    private final LocalTime quietStart;
    private final LocalTime quietEnd;

    @Autowired
    public ProactiveNotificationPolicy(
            @Value("${minikun.proactive.enabled:true}") boolean enabled,
            @Value("${minikun.proactive.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.proactive.quiet-hours.start:22:00}") String quietStart,
            @Value("${minikun.proactive.quiet-hours.end:07:00}") String quietEnd) {
        this(enabled, parseZone(zone), parseTime(quietStart, "quiet-hours.start"),
                parseTime(quietEnd, "quiet-hours.end"));
    }

    public ProactiveNotificationPolicy(boolean enabled, ZoneId zone, LocalTime quietStart, LocalTime quietEnd) {
        this.enabled = enabled;
        this.zone = Objects.requireNonNull(zone, "proactive zone must not be null");
        this.quietStart = Objects.requireNonNull(quietStart, "quiet start must not be null");
        this.quietEnd = Objects.requireNonNull(quietEnd, "quiet end must not be null");
    }

    public boolean enabled() {
        return enabled;
    }

    public ZoneId zone() {
        return zone;
    }

    public boolean allows(Instant now) {
        Objects.requireNonNull(now, "notification time must not be null");
        return enabled && !quietHours(now);
    }

    public boolean quietHours(Instant now) {
        Objects.requireNonNull(now, "notification time must not be null");
        LocalTime localTime = now.atZone(zone).toLocalTime();
        if (quietStart.equals(quietEnd)) {
            return false;
        }
        if (quietStart.isBefore(quietEnd)) {
            return !localTime.isBefore(quietStart) && localTime.isBefore(quietEnd);
        }
        return !localTime.isBefore(quietStart) || localTime.isBefore(quietEnd);
    }

    public boolean allows(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return allows(clock.instant());
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(Objects.requireNonNullElse(value, "Asia/Bangkok").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid proactive timezone", exception);
        }
    }

    private static LocalTime parseTime(String value, String name) {
        try {
            return LocalTime.parse(Objects.requireNonNullElse(value, "").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid proactive " + name, exception);
        }
    }
}
