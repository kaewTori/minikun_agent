package com.minikun.pcs;

import com.minikun.character.model.LoadingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractLiteralTermSelectionStrategyTest {
    private final FutureLiteralStrategy strategy = new FutureLiteralStrategy();
    private final McsModule module = new McsModule("interests", LoadingPolicy.DYNAMIC,
            List.of("Rendered"), Optional.of(new McsSelectionMetadata(List.of("Java"))));

    @Test
    void sharedFlowEvaluatesCurrentMessageAndReturnsAdapterDecision() {
        McsSelectionDecision decision = strategy.select(module,
                new McsSelectionContext("I write JAVA", ""));

        assertTrue(decision.selected());
        assertEquals("future-selected", decision.selector());
        assertEquals("Future module matched", decision.reason());
    }

    @Test
    void sharedFlowEvaluatesHistoryAndSkipsWithoutMetadataMatch() {
        assertTrue(strategy.select(module,
                new McsSelectionContext("unrelated", "Earlier Java discussion")).selected());

        McsSelectionDecision decision = strategy.select(
                new McsModule("interests", LoadingPolicy.DYNAMIC, List.of()),
                new McsSelectionContext("unrelated", "also unrelated"));

        assertFalse(decision.selected());
        assertEquals("Future module skipped", decision.reason());
    }

    @Test
    void sharedFlowIsDeterministicAndGuardsTheSupportedModule() {
        McsSelectionContext context = new McsSelectionContext("Java", "history");
        assertEquals(strategy.select(module, context), strategy.select(module, context));

        assertThrows(IllegalArgumentException.class, () -> strategy.select(
                new McsModule("catchphrases", LoadingPolicy.DYNAMIC, List.of()), context));
    }

    @Test
    void futureAdapterCanBeRegisteredWithoutChangingSharedFlow() {
        McsSelectionStrategy registered = ModuleSelectionStrategyRegistries.fromNamedStrategies(
                Map.of("interests", strategy)).strategyFor(SectionKind.INTERESTS);

        McsSelectionDecision decision = registered.select(module,
                new McsSelectionContext("Java", ""));

        assertEquals(strategy, registered);
        assertTrue(decision.selected());
    }

    private static final class FutureLiteralStrategy extends AbstractLiteralTermSelectionStrategy {
        private FutureLiteralStrategy() {
            super(new LiteralTermMatcher());
        }

        @Override
        protected SectionKind supportedSectionKind() {
            return SectionKind.INTERESTS;
        }

        @Override
        protected String unsupportedModuleMessage() {
            return "FutureLiteralStrategy only supports interests";
        }

        @Override
        protected McsSelectionDecision decisionFor(boolean matched) {
            return matched
                    ? McsSelectionDecision.selected("future-selected", "Future module matched")
                    : McsSelectionDecision.skipped("future-skipped", "Future module skipped");
        }
    }
}
