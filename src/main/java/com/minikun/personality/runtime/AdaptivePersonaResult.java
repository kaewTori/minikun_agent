package com.minikun.personality.runtime;

import com.minikun.personality.arbitration.PersonaActivation;
import com.minikun.personality.signal.PersonaSelectionSignals;

public record AdaptivePersonaResult(PersonaSelectionSignals signals, PersonaActivation activation) {}
