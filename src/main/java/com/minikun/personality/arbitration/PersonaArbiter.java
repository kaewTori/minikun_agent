package com.minikun.personality.arbitration;

import com.minikun.personality.signal.PersonaSelectionSignals;
import java.util.ArrayList;

/** Deterministic overlay policy. Core identity, values and boundaries are never arbitrated here. */
public final class PersonaArbiter {
    public PersonaActivation arbitrate(PersonaSelectionSignals signals) {
        var overlays = new ArrayList<String>();
        var reasons = new ArrayList<String>();
        if (signals.profileAvailable()) { overlays.add("user-profile"); reasons.add("profile available"); }
        if (signals.preferenceAvailable()) { overlays.add("user-preferences"); reasons.add("preferences available"); }
        if (signals.memoryAvailable()) { overlays.add("memory-aware"); reasons.add("recalled memories available"); }
        if (signals.searchRequested()) { overlays.add("search-aware"); reasons.add("search requested"); }
        if (signals.toolInvocationRequested()) {
            overlays.add("tool-aware");
            reasons.add(signals.toolRequiresConfirmation() ? "tool requires confirmation" : "tool requested");
        }
        if (signals.moodActive()) { overlays.add("mood-" + signals.mood().toLowerCase()); reasons.add("active mood"); }
        return new PersonaActivation(overlays, reasons);
    }
}
