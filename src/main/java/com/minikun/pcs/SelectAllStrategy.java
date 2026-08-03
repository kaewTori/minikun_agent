package com.minikun.pcs;

public final class SelectAllStrategy implements McsSelectionStrategy {
    @Override
    public McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        return McsSelectionDecision.selected("reference", "Reference strategy selects every module");
    }
}