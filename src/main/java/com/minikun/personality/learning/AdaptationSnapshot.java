package com.minikun.personality.learning;

import com.minikun.personality.model.AdaptationSignal;
import com.minikun.personality.model.Preference;
import java.time.Instant;
import java.util.List;

public record AdaptationSnapshot(
        String ownerId,
        boolean enabled,
        List<Preference> activePreferences,
        List<AdaptationSignal> evidence,
        Instant generatedAt) {
    public AdaptationSnapshot {
        activePreferences = activePreferences == null ? List.of() : List.copyOf(activePreferences);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
