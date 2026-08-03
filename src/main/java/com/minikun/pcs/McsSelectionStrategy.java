package com.minikun.pcs;

@FunctionalInterface
public interface McsSelectionStrategy {
    McsSelectionDecision select(McsModule module, McsSelectionContext context);
}
