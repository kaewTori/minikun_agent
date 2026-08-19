package com.minikun.calendar;

import java.time.Instant;
import java.util.Objects;

/** One concrete occurrence from an external calendar feed. */
public record ExternalCalendarEvent(
        String uid,
        String title,
        String description,
        String location,
        Instant startsAt,
        Instant endsAt,
        boolean allDay,
        String source) {
    public ExternalCalendarEvent {
        uid = require(uid, "calendar uid");
        title = require(title, "calendar title");
        description = Objects.requireNonNullElse(description, "").trim();
        location = Objects.requireNonNullElse(location, "").trim();
        Objects.requireNonNull(startsAt, "calendar start must not be null");
        Objects.requireNonNull(endsAt, "calendar end must not be null");
        source = require(source, "calendar source");
        if (endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("calendar end must not be before start");
        }
    }

    public String occurrenceId() {
        return uid + ":" + startsAt;
    }

    private static String require(String value, String name) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
