package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModuleSelectionStrategyRegistryTest {
    @Test
    void everySectionKindResolvesToExactlyOneStrategy() {
        McsSelectionStrategy fallback = new SelectAllStrategy();
        McsSelectionStrategy interests = new InterestsSelectionStrategy();
        ModuleSelectionStrategyRegistry registry = ModuleSelectionStrategyRegistry.of(
                Map.of(SectionKind.INTERESTS, interests), fallback);

        for (SectionKind sectionKind : SectionKind.values()) {
            assertSame(sectionKind == SectionKind.INTERESTS ? interests : fallback,
                    registry.strategyFor(sectionKind));
            assertSame(registry.strategyFor(sectionKind), registry.strategyFor(sectionKind));
        }
    }

    @Test
    void duplicateRegistrationsFailBeforeRegistryCreation() {
        ModuleSelectionStrategyRegistry.Builder builder =
                ModuleSelectionStrategyRegistry.builder(new SelectAllStrategy());
        builder.register(SectionKind.INTERESTS, new InterestsSelectionStrategy());

        assertThrows(IllegalArgumentException.class,
                () -> builder.register(SectionKind.INTERESTS, new InterestsSelectionStrategy()));
    }

    @Test
    void invalidFallbackFailsBeforeRegistryCreation() {
        assertThrows(NullPointerException.class,
                () -> ModuleSelectionStrategyRegistry.of(Map.of(), null));
        assertThrows(NullPointerException.class,
                () -> ModuleSelectionStrategyRegistry.builder(null));
    }

    @Test
    void nullLookupIsRejected() {
        ModuleSelectionStrategyRegistry registry = ModuleSelectionStrategyRegistry.of(
                Map.of(), new SelectAllStrategy());

        assertThrows(NullPointerException.class, () -> registry.strategyFor(null));
    }
}