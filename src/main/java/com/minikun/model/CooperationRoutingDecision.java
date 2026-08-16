package com.minikun.model;

public record CooperationRoutingDecision(
        CooperationRisk risk,
        boolean needsExpert,
        String reason) {
    public static CooperationRoutingDecision low() {
        return new CooperationRoutingDecision(CooperationRisk.LOW, false, "low_risk");
    }
}
