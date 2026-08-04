package com.minikun.pcs;

import com.minikun.character.model.LoadingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatchphrasesSelectionStrategyTest {
    private final CatchphrasesSelectionStrategy strategy = new CatchphrasesSelectionStrategy();
    private final McsModule catchphrases = new McsModule("catchphrases", LoadingPolicy.DYNAMIC,
            List.of("Rendered catchphrase"),
            Optional.of(new McsSelectionMetadata(List.of("plan", "explain"))));

    @Test
    void selectsWhenConfiguredMetadataMatches() {
        McsSelectionDecision decision = strategy.select(catchphrases,
                new McsSelectionContext("Please explain this", ""));

        assertTrue(decision.selected());
        assertEquals(CatchphrasesSelectionStrategy.NAME, decision.selector());
        assertEquals("Catchphrase matched", decision.reason());
    }

    @Test
    void evaluatesConversationHistoryAndSkipsWithoutMatch() {
        assertTrue(strategy.select(catchphrases,
                new McsSelectionContext("unrelated", "Earlier planning discussion")).selected());
        McsSelectionDecision decision = strategy.select(catchphrases,
                new McsSelectionContext("unrelated", "also unrelated"));

        assertFalse(decision.selected());
        assertEquals("No matching catchphrase", decision.reason());
    }

    @Test
    void rejectsOtherModulesAndSupportsMissingMetadata() {
        assertThrows(IllegalArgumentException.class, () -> strategy.select(
                new McsModule("interests", LoadingPolicy.DYNAMIC, List.of()),
                new McsSelectionContext("plan", "")));
        assertFalse(new CatchphrasesSelectionStrategy().select(
                new McsModule("catchphrases", LoadingPolicy.DYNAMIC, List.of()),
                new McsSelectionContext("plan", "")).selected());
    }
}