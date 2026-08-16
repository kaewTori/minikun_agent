package com.minikun.personality.runtime;

import com.minikun.personality.arbitration.PersonaArbiter;
import com.minikun.personality.signal.PersonaRuntimeInput;
import com.minikun.personality.signal.PersonaSelectionSignals;
import com.minikun.personality.signal.PersonaSignalProjector;
import java.util.Objects;

public final class AdaptivePersonaRuntime {
    private final PersonaSignalProjector projector;
    private final PersonaArbiter arbiter;

    public AdaptivePersonaRuntime() { this(new PersonaSignalProjector(), new PersonaArbiter()); }
    public AdaptivePersonaRuntime(PersonaSignalProjector projector, PersonaArbiter arbiter) {
        this.projector = Objects.requireNonNull(projector);
        this.arbiter = Objects.requireNonNull(arbiter);
    }
    public AdaptivePersonaResult evaluate(PersonaRuntimeInput input) {
        Objects.requireNonNull(input, "input");
        PersonaSelectionSignals signals = projector.project(input);
        return new AdaptivePersonaResult(signals, arbiter.arbitrate(signals));
    }
}
