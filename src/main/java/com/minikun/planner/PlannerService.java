package com.minikun.planner;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class PlannerService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Bangkok");

    private final PlannerStore store;
    private final java.time.Clock clock;

    public PlannerService(PlannerStore store, java.time.Clock clock) {
        this.store = Objects.requireNonNull(store, "planner store must not be null");
        this.clock = Objects.requireNonNull(clock, "planner clock must not be null");
    }

    public PlannerEvent create(
            ConversationId conversationId,
            String title,
            String note,
            String at,
            String timezone,
            int remindBeforeMinutes,
            String recurrence) {
        ZoneId zone = zone(timezone);
        Instant startsAt = parseDateTime(at, zone);
        if (startsAt.isBefore(clock.instant())) {
            throw new IllegalArgumentException("planner event time must be in the future");
        }
        if (remindBeforeMinutes < 0 || remindBeforeMinutes > 7 * 24 * 60) {
            throw new IllegalArgumentException("remind_before_minutes must be between 0 and 10080");
        }
        PlannerRecurrence parsedRecurrence;
        try {
            parsedRecurrence = PlannerRecurrence.parse(recurrence);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("recurrence must be NONE, DAILY, or WEEKLY");
        }
        Instant now = clock.instant();
        PlannerEvent event = new PlannerEvent(
                UUID.randomUUID(), conversationId.value(), requireText(title, "title"),
                Objects.requireNonNullElse(note, "").trim(), startsAt, zone, remindBeforeMinutes, parsedRecurrence,
                "ACTIVE", startsAt.minusSeconds(remindBeforeMinutes * 60L), now, now);
        return store.create(event);
    }

    public List<PlannerEvent> list(ConversationId conversationId) {
        return store.list(conversationId.value());
    }

    public PlannerEvent update(
            ConversationId conversationId,
            UUID id,
            String title,
            String note,
            String at,
            String timezone,
            Integer remindBeforeMinutes,
            String recurrence) {
        PlannerEvent current = store.find(id, conversationId.value())
                .orElseThrow(() -> new IllegalArgumentException("planner event was not found"));
        ZoneId zone = timezone == null || timezone.isBlank() ? current.timezone() : zone(timezone);
        Instant startsAt = at == null || at.isBlank() ? current.startsAt() : parseDateTime(at, zone);
        int remind = remindBeforeMinutes == null ? current.remindBeforeMinutes() : remindBeforeMinutes;
        if (remind < 0 || remind > 7 * 24 * 60) {
            throw new IllegalArgumentException("remind_before_minutes must be between 0 and 10080");
        }
        PlannerRecurrence parsedRecurrence;
        try {
            parsedRecurrence = recurrence == null || recurrence.isBlank()
                    ? current.recurrence() : PlannerRecurrence.parse(recurrence);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("recurrence must be NONE, DAILY, or WEEKLY");
        }
        PlannerEvent updated = new PlannerEvent(
                current.id(), current.conversationId(),
                title == null || title.isBlank() ? current.title() : title.trim(),
                note == null ? current.note() : note.trim(), startsAt, zone, remind, parsedRecurrence,
                current.status(), startsAt.minusSeconds(remind * 60L), current.createdAt(), clock.instant());
        return store.update(updated);
    }

    public boolean cancel(ConversationId conversationId, UUID id) {
        return store.cancel(id, conversationId.value(), clock.instant());
    }

    public boolean acknowledge(ConversationId conversationId, UUID id) {
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Optional<PlannerEvent> event = store.find(id, conversationId.value());
        if (event.isEmpty()) return false;
        Instant now = clock.instant();
        store.recordAction(UUID.randomUUID(), conversationId.value(), id, "ACKNOWLEDGED", null, now);
        return true;
    }

    public PlannerEvent snooze(
            ConversationId conversationId,
            UUID id,
            String until,
            String timezone) {
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        PlannerEvent original = store.find(id, conversationId.value())
                .orElseThrow(() -> new IllegalArgumentException("planner event was not found"));
        ZoneId zone = timezone == null || timezone.isBlank() ? original.timezone() : zone(timezone);
        Instant snoozedUntil = parseDateTime(until, zone);
        Instant now = clock.instant();
        if (!snoozedUntil.isAfter(now)) {
            throw new IllegalArgumentException("snooze time must be in the future");
        }
        PlannerEvent snoozed = new PlannerEvent(
                UUID.randomUUID(), conversationId.value(), original.title(),
                "Snoozed from " + original.id() + (original.note().isBlank() ? "" : ". " + original.note()),
                snoozedUntil, zone, 0, PlannerRecurrence.NONE, "ACTIVE", snoozedUntil, now, now);
        PlannerEvent saved = store.create(snoozed);
        store.recordAction(UUID.randomUUID(), conversationId.value(), id, "SNOOZED", snoozedUntil, now);
        return saved;
    }

    public List<PlannerEvent> due(Instant now) {
        return store.findDue(now);
    }

    public List<PlannerEvent> upcoming(Instant from, Instant to) {
        Objects.requireNonNull(from, "planner range start must not be null");
        Objects.requireNonNull(to, "planner range end must not be null");
        if (!to.isAfter(from)) throw new IllegalArgumentException("planner range end must be after start");
        return store.listUpcoming(from, to);
    }

    public void markDelivered(PlannerEvent event, Instant now) {
        store.markDelivered(event, now);
    }

    public Map<String, Object> describe(PlannerEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", event.id().toString());
        result.put("conversation_id", event.conversationId());
        result.put("title", event.title());
        result.put("note", event.note());
        result.put("starts_at", event.startsAt().toString());
        result.put("timezone", event.timezone().getId());
        result.put("remind_before_minutes", event.remindBeforeMinutes());
        result.put("recurrence", event.recurrence().name());
        result.put("status", event.status());
        result.put("next_notification_at", event.nextNotifyAt().toString());
        return result;
    }

    public List<Map<String, Object>> describeAll(List<PlannerEvent> events) {
        return events.stream().map(this::describe).toList();
    }

    private ZoneId zone(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_ZONE.getId() : value.trim();
        try {
            return ZoneId.of(normalized);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid IANA timezone: " + normalized);
        }
    }

    private Instant parseDateTime(String value, ZoneId zone) {
        String normalized = requireText(value, "at");
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // Continue with offset, zoned, and local formats used by tool callers.
        }
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with a local date-time in the supplied timezone.
        }
        try {
            return ZonedDateTime.parse(normalized, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with local formats.
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "at must be an ISO-8601 date/time, for example 2026-08-20T09:00:00+07:00");
            }
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
