package com.minikun.personality.signal;

import com.minikun.personality.model.Preference;
import java.util.List;
import java.time.Duration;
import java.time.Instant;

public final class PersonaSignalProjector {
    public PersonaSelectionSignals project(PersonaRuntimeInput input) {
        List<String> keys = input.preferences().stream()
                .filter(preference -> preference.activeAt(Instant.now(), Duration.ofDays(365), .5))
                .map(Preference::key).distinct().sorted().toList();
        return new PersonaSelectionSignals(
                !input.recalledMemories().isEmpty(), input.recalledMemories().size(),
                input.profile().available(), !keys.isEmpty(), input.toolInvocationRequested(),
                input.toolRequiresConfirmation(), input.searchRequested(), input.mood().active(),
                input.mood().mood().name(), keys);
    }
}
