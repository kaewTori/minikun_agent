package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McsSelector {
    private static final String POLICY_SELECTOR = "loading-policy";
    private static final String MISSING_STRATEGY_SELECTOR = "none";

    private final Map<String, McsSelectionStrategy> strategies;

    public McsSelector() {
        this(defaultStrategies());
    }

    public McsSelector(Map<String, McsSelectionStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        Map<String, McsSelectionStrategy> copy = new LinkedHashMap<>();
        strategies.forEach((module, strategy) -> copy.put(
                Objects.requireNonNull(module, "strategy module"),
                Objects.requireNonNull(strategy, "strategy")));
        this.strategies = Map.copyOf(copy);
    }

    public McsSelectionResult select(CharacterSpecification specification, McsSelectionContext context) {
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(context, "context");

        List<McsModule> selected = new java.util.ArrayList<>();
        List<McsSelectionDiagnostic> diagnostics = new java.util.ArrayList<>();
        for (McsModule module : McsModule.orderedFrom(specification)) {
            McsSelectionDecision decision = decisionFor(module, context);
            diagnostics.add(new McsSelectionDiagnostic(module.name(), module.loadingPolicy(),
                    decision.selected(), decision.selector(), decision.reason()));
            if (decision.selected()) {
                selected.add(module);
            }
        }
        return new McsSelectionResult(selected, diagnostics);
    }

    private McsSelectionDecision decisionFor(McsModule module, McsSelectionContext context) {
        if (module.loadingPolicy() == LoadingPolicy.ALWAYS) {
            return McsSelectionDecision.selected(POLICY_SELECTOR, "ALWAYS");
        }
        McsSelectionStrategy strategy = strategies.get(module.name());
        if (strategy == null) {
            return McsSelectionDecision.skipped(MISSING_STRATEGY_SELECTOR, "No strategy configured");
        }
        return Objects.requireNonNull(strategy.select(module, context),
                "strategy decision must not be null");
    }

    private static Map<String, McsSelectionStrategy> defaultStrategies() {
        McsSelectionStrategy strategy = new ModuleNameMentionStrategy();
        Map<String, McsSelectionStrategy> defaults = new LinkedHashMap<>();
        for (String module : List.of("identity", "personality", "values", "communication", "behavior",
                "reasoning", "interests", "boundaries", "catchphrases")) {
            defaults.put(module, strategy);
        }
        return defaults;
    }
}
