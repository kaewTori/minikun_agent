package com.minikun.pcs;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ImmutableModuleSelectionStrategyRegistry
        implements ModuleSelectionStrategyRegistry {
    private final Map<SectionKind, McsSelectionStrategy> strategies;

    public ImmutableModuleSelectionStrategyRegistry(
            Map<SectionKind, McsSelectionStrategy> strategies,
            McsSelectionStrategy fallback) {
        Objects.requireNonNull(strategies, "strategies");
        Objects.requireNonNull(fallback, "fallback");

        EnumMap<SectionKind, McsSelectionStrategy> resolved = new EnumMap<>(SectionKind.class);
        for (Map.Entry<SectionKind, McsSelectionStrategy> entry : strategies.entrySet()) {
            SectionKind sectionKind = Objects.requireNonNull(entry.getKey(), "section kind");
            McsSelectionStrategy strategy = Objects.requireNonNull(entry.getValue(), "strategy");
            if (resolved.put(sectionKind, strategy) != null) {
                throw new IllegalArgumentException("Duplicate strategy for " + sectionKind);
            }
        }
        for (SectionKind sectionKind : SectionKind.values()) {
            resolved.putIfAbsent(sectionKind, fallback);
        }
        this.strategies = Map.copyOf(resolved);
    }

    @Override
    public McsSelectionStrategy strategyFor(SectionKind sectionKind) {
        return strategies.get(Objects.requireNonNull(sectionKind, "section kind"));
    }
}