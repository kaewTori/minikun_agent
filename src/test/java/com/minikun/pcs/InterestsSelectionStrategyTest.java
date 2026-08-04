package com.minikun.pcs;

import com.minikun.character.model.LoadingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterestsSelectionStrategyTest {
    private final InterestsSelectionStrategy strategy = new InterestsSelectionStrategy();
    private final McsModule interests = new McsModule("interests", LoadingPolicy.DYNAMIC, List.of("Rendered only"),
            Optional.of(new McsSelectionMetadata(List.of("Artificial Intelligence", "Linux"))));

    @Test
    void matchesConfiguredMetadataInCurrentMessageWithNormalization() {
        McsSelectionDecision decision = strategy.select(interests,
                new McsSelectionContext("  I enjoy ARTIFICIAL   INTELLIGENCE  ", ""));

        assertTrue(decision.selected());
        assertEquals("Interest matched", decision.reason());
        assertEquals(InterestsSelectionStrategy.NAME, decision.selector());
    }

    @Test
    void matchesConfiguredMetadataInConversationHistory() {
        McsSelectionDecision decision = strategy.select(interests,
                new McsSelectionContext("unrelated", "Earlier discussion about linux"));

        assertTrue(decision.selected());
    }

    @Test
    void skipsWhenNoConfiguredMetadataMatches() {
        McsSelectionDecision decision = strategy.select(interests,
                new McsSelectionContext("unrelated", "also unrelated"));

        assertFalse(decision.selected());
        assertEquals("No matching interest", decision.reason());
    }

    @Test
    void selectsFromInterestSignalAfterLiteralMatchingFails() {
        McsSelectionContext context = new McsSelectionContext(
                "unrelated", "also unrelated",
                ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY, new InterestSelectionSignals(true));

        McsSelectionDecision decision = strategy.select(interests, context);

        assertTrue(decision.selected());
        assertEquals(InterestsSelectionStrategy.NAME, decision.selector());
        assertEquals("Interest selection signal matched", decision.reason());
    }

    @Test
    void literalMatchTakesPrecedenceOverInterestSignal() {
        McsSelectionContext context = new McsSelectionContext(
                "Linux", "",
                ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY, new InterestSelectionSignals(true));

        McsSelectionDecision decision = strategy.select(interests, context);

        assertTrue(decision.selected());
        assertEquals("Interest matched", decision.reason());
    }

    @Test
    void emptyInterestSignalPreservesExistingSkipReason() {
        McsSelectionDecision decision = strategy.select(interests,
                new McsSelectionContext("unrelated", "also unrelated"));

        assertFalse(decision.selected());
        assertEquals("No matching interest", decision.reason());
    }

    @Test
    void signalDrivenSelectionIsDeterministic() {
        McsSelectionContext context = new McsSelectionContext(
                "unrelated", "also unrelated",
                ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY, new InterestSelectionSignals(true));

        assertEquals(strategy.select(interests, context), strategy.select(interests, context));
    }

    @Test
    void repeatedEvaluationIsDeterministicAndMetadataIsImmutable() {
        McsSelectionContext context = new McsSelectionContext("Talk about Linux", "Earlier");

        assertEquals(strategy.select(interests, context), strategy.select(interests, context));
        assertThrows(UnsupportedOperationException.class,
                () -> interests.selectionMetadata().orElseThrow().literalTerms().add("later"));
    }

    @Test
    void modulesWithoutSelectionMetadataRemainValidAndSkip() {
        McsModule module = new McsModule("interests", LoadingPolicy.DYNAMIC, List.of("Rendered"));

        assertFalse(strategy.select(module, new McsSelectionContext("Linux", "")).selected());
    }
}