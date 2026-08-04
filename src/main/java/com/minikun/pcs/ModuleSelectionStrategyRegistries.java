package com.minikun.pcs;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ModuleSelectionStrategyRegistries {
    private ModuleSelectionStrategyRegistries() {
    }

    public static ModuleSelectionStrategyRegistry defaultRegistry() {
        LiteralTermMatcher matcher = new LiteralTermMatcher();
        return ModuleSelectionStrategyRegistry.of(
            Map.of(
                SectionKind.INTERESTS, new InterestsSelectionStrategy(matcher),
                SectionKind.CATCHPHRASES, new CatchphrasesSelectionStrategy(matcher)),
                new SelectAllStrategy());
    }

    public static ModuleSelectionStrategyRegistry fromNamedStrategies(
            Map<String, McsSelectionStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        EnumMap<SectionKind, McsSelectionStrategy> byKind = new EnumMap<>(SectionKind.class);
        strategies.forEach((moduleName, strategy) -> byKind.put(
                SectionKind.fromModuleName(Objects.requireNonNull(moduleName, "strategy module")),
                Objects.requireNonNull(strategy, "strategy")));
        return ModuleSelectionStrategyRegistry.of(byKind, new SelectAllStrategy());
    }
}