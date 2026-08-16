package com.minikun.personality.arbitration;

import java.util.List;

public record PersonaActivation(List<String> overlays, List<String> reasons) {
    public PersonaActivation {
        overlays = overlays == null ? List.of() : List.copyOf(overlays);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
