package com.minikun.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.pcs.SelectionContextFactory;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.personality.signal.PersonaSelectionSignals;
import org.junit.jupiter.api.Test;

class PersonaSelectionPropagationTest {
    @Test
    void mapsAdaptiveSignalsIntoMcsMemoryContract() {
        var signals = new PersonaSelectionSignals(true, 3, true, true, false, false,
                false, true, "FOCUSED", java.util.List.of("language"));
        var context = new SelectionContextFactory().create("hello", "history",
                SearchSelectionSignals.EMPTY, null, signals);

        assertEquals(signals, context.personaSelectionSignals());
        assertEquals(3, context.memorySelectionSignals().recalledMemoryCount());
        assertEquals(true, context.memorySelectionSignals().memoryRecallPerformed());
    }
}
