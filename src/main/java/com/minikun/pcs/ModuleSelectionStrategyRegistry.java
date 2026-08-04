package com.minikun.pcs;

import java.util.Map;
import java.util.Objects;
import java.util.EnumMap;

public interface ModuleSelectionStrategyRegistry {
    McsSelectionStrategy strategyFor(SectionKind sectionKind);

    static Builder builder(McsSelectionStrategy fallback) {
        return new Builder(fallback);
    }

    static ModuleSelectionStrategyRegistry of(
            Map<SectionKind, McsSelectionStrategy> strategies,
            McsSelectionStrategy fallback) {
        return new ImmutableModuleSelectionStrategyRegistry(strategies, fallback);
    }

    final class Builder {
        private final McsSelectionStrategy fallback;
        private final EnumMap<SectionKind, McsSelectionStrategy> strategies =
                new EnumMap<>(SectionKind.class);

        private Builder(McsSelectionStrategy fallback) {
            this.fallback = Objects.requireNonNull(fallback, "fallback");
        }

        public Builder register(SectionKind sectionKind, McsSelectionStrategy strategy) {
            Objects.requireNonNull(sectionKind, "section kind");
            Objects.requireNonNull(strategy, "strategy");
            if (strategies.putIfAbsent(sectionKind, strategy) != null) {
                throw new IllegalArgumentException("Duplicate strategy for " + sectionKind);
            }
            return this;
        }

        public ModuleSelectionStrategyRegistry build() {
            return new ImmutableModuleSelectionStrategyRegistry(strategies, fallback);
        }
    }
}