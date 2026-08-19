package com.minikun.calendar;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtStart;

/** Parses RFC 5545 feeds and expands recurring VEVENTs into concrete occurrences. */
public final class IcsCalendarParser {
    public List<ExternalCalendarEvent> parse(
            String payload,
            Instant from,
            Instant to,
            ZoneId defaultZone,
            String source) {
        Objects.requireNonNull(payload, "calendar payload must not be null");
        Objects.requireNonNull(from, "calendar range start must not be null");
        Objects.requireNonNull(to, "calendar range end must not be null");
        Objects.requireNonNull(defaultZone, "calendar default zone must not be null");
        if (!to.isAfter(from)) throw new IllegalArgumentException("calendar range end must be after start");
        try {
            var calendar = new CalendarBuilder().build(new StringReader(payload));
            Map<String, ExternalCalendarEvent> unique = new LinkedHashMap<>();
            for (Object component : calendar.getComponents(Component.VEVENT)) {
                VEvent event = (VEvent) component;
                if ("CANCELLED".equalsIgnoreCase(value(event, Property.STATUS))) continue;
                addOccurrences(unique, event, from, to, defaultZone, source);
            }
            return unique.values().stream()
                    .sorted(Comparator.comparing(ExternalCalendarEvent::startsAt)
                            .thenComparing(ExternalCalendarEvent::title))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalArgumentException("external calendar feed is not valid iCalendar data", exception);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addOccurrences(
            Map<String, ExternalCalendarEvent> unique,
            VEvent event,
            Instant from,
            Instant to,
            ZoneId zone,
            String source) {
        DtStart startProperty = (DtStart) event.getProperty(Property.DTSTART)
                .orElseThrow(() -> new IllegalArgumentException("calendar event is missing DTSTART"));
        Temporal eventStart = startProperty.getDate();
        Period query = queryPeriod(eventStart, from, to, zone);
        String uid = text(value(event, Property.UID), "event-" + Integer.toHexString(event.hashCode()));
        String title = text(value(event, Property.SUMMARY), "Untitled event");
        String description = value(event, Property.DESCRIPTION);
        String location = value(event, Property.LOCATION);
        boolean allDay = eventStart instanceof LocalDate;
        for (Object item : event.calculateRecurrenceSet(query)) {
            Period occurrence = (Period) item;
            Instant startsAt = occurrence.toInterval(zone).getStart();
            Instant endsAt = occurrence.toInterval(zone).getEnd();
            if (endsAt.isBefore(from) || !startsAt.isBefore(to)) continue;
            ExternalCalendarEvent mapped = new ExternalCalendarEvent(
                    uid, title, description, location, startsAt, endsAt, allDay, source);
            unique.put(mapped.occurrenceId(), mapped);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Period queryPeriod(Temporal type, Instant from, Instant to, ZoneId zone) {
        if (type instanceof Instant) return new Period(from, to);
        if (type instanceof OffsetDateTime offset) {
            return new Period(from.atOffset(offset.getOffset()), to.atOffset(offset.getOffset()));
        }
        if (type instanceof ZonedDateTime zoned) {
            return new Period(from.atZone(zoned.getZone()), to.atZone(zoned.getZone()));
        }
        if (type instanceof LocalDate) {
            return new Period(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate().plusDays(1));
        }
        return new Period(from.atZone(zone).toLocalDateTime(), to.atZone(zone).toLocalDateTime());
    }

    private String value(VEvent event, String name) {
        return event.getProperty(name).map(property -> ((Property) property).getValue()).orElse("");
    }

    private String text(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isBlank() ? fallback : normalized;
    }
}
