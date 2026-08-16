package com.minikun.personality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.personality.arbitration.PersonaArbiter;
import com.minikun.personality.signal.PersonaSelectionSignals;
import org.junit.jupiter.api.Test;

/** Small deterministic regression suite for persona invariants. */
class PersonaEvaluationTest {
    @Test
    void coreIdentityIsNeverRepresentedAsAnAdaptiveOverlay() {
        var signals = new PersonaSelectionSignals(true, 1, true, true, true, true,
                true, true, "CONCERNED", java.util.List.of("style"));
        var overlays = new PersonaArbiter().arbitrate(signals).overlays();

        assertFalse(overlays.contains("identity"));
        assertFalse(overlays.contains("values"));
        assertFalse(overlays.contains("boundaries"));
        assertTrue(overlays.contains("memory-aware"));
    }
}
