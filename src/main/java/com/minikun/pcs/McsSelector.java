package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class McsSelector {
    private static final String POLICY_SELECTOR = "loading-policy";
    private final ModuleSelectionStrategyRegistry strategyRegistry;

    public McsSelector() {
        this(ModuleSelectionStrategyRegistries.defaultRegistry());
    }

    public McsSelector(Map<String, McsSelectionStrategy> strategies) {
        this(ModuleSelectionStrategyRegistries.fromNamedStrategies(strategies));
    }

    public McsSelector(ModuleSelectionStrategyRegistry strategyRegistry) {
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry, "strategyRegistry");
    }

    public McsSelectionResult select(CharacterSpecification specification, McsSelectionContext context) {
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(context, "context");

        List<McsSelectionDecision> decisions = new java.util.ArrayList<>();
        for (McsModule module : McsModule.orderedFrom(specification)) {
            long started = System.nanoTime();
            McsSelectionDecision decision = decisionFor(module, context).forModule(module);
            decisions.add(decision);
            log.info("process=module_selection module={} selected={} duration_ms={}",
                    module.name(), decision.selected(), (System.nanoTime() - started) / 1_000_000);
        }
        return new McsSelectionResult(decisions);
    }

    private McsSelectionDecision decisionFor(McsModule module, McsSelectionContext context) {
        if (module.loadingPolicy() == LoadingPolicy.ALWAYS) {
            return McsSelectionDecision.selected(POLICY_SELECTOR, "ALWAYS");
        }
        McsSelectionStrategy strategy = strategyRegistry.strategyFor(module.sectionKind());
        return Objects.requireNonNull(strategy.select(module, context),
                "strategy decision must not be null");
    }
}
