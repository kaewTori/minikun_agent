package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.TimelineEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Writes sanitized, idempotent lifecycle events; raw prompts and secrets are never accepted here. */
public final class PersonalTimelineRecorder {
    private final PersonalLoopStore store;
    private final Clock clock;

    public PersonalTimelineRecorder(PersonalLoopStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    public TimelineEvent record(String ownerId, String eventType, String sourceType, String sourceId,
            String title, String summary, Map<String, Object> details, Instant occurredAt) {
        Instant now = clock.instant();
        return store.save(new TimelineEvent(UUID.randomUUID(), ownerId, eventType, sourceType, sourceId,
                title, summary, details, occurredAt == null ? now : occurredAt, now));
    }
}
