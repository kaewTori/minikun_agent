package com.minikun.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Cached read model for agenda, reminders, and daily briefing consumers. */
public final class ExternalCalendarService {
    private final IcsCalendarClient client;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile Cache cache;

    public ExternalCalendarService(IcsCalendarClient client, Clock clock, Duration cacheTtl) {
        this.client = Objects.requireNonNull(client, "calendar client must not be null");
        this.clock = Objects.requireNonNull(clock, "calendar clock must not be null");
        this.cacheTtl = requirePositive(cacheTtl);
    }

    public List<ExternalCalendarEvent> events(Instant from, Instant to) {
        Objects.requireNonNull(from, "calendar range start must not be null");
        Objects.requireNonNull(to, "calendar range end must not be null");
        Instant now = clock.instant();
        Cache current = cache;
        if (current != null && now.isBefore(current.expiresAt())
                && !from.isBefore(current.from()) && !to.isAfter(current.to())) {
            return within(current.events(), from, to);
        }
        synchronized (this) {
            current = cache;
            if (current == null || !now.isBefore(current.expiresAt())
                    || from.isBefore(current.from()) || to.isAfter(current.to())) {
                current = new Cache(from, to, now.plus(cacheTtl), List.copyOf(client.events(from, to)));
                cache = current;
            }
        }
        return within(current.events(), from, to);
    }

    private List<ExternalCalendarEvent> within(List<ExternalCalendarEvent> events, Instant from, Instant to) {
        return events.stream()
                .filter(event -> event.endsAt().isAfter(from) && event.startsAt().isBefore(to))
                .toList();
    }

    private Duration requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("calendar cache TTL must be positive");
        }
        return value;
    }

    private record Cache(Instant from, Instant to, Instant expiresAt, List<ExternalCalendarEvent> events) {}
}
