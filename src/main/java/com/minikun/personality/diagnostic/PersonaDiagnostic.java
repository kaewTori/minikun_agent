package com.minikun.personality.diagnostic;

import com.minikun.personality.runtime.AdaptivePersonaResult;
import java.util.List;

public record PersonaDiagnostic(List<String> activeOverlays, List<String> reasons, int recalledMemoryCount) {
    public static PersonaDiagnostic from(AdaptivePersonaResult result) {
        return new PersonaDiagnostic(result.activation().overlays(), result.activation().reasons(),
                result.signals().recalledMemoryCount());
    }
}
