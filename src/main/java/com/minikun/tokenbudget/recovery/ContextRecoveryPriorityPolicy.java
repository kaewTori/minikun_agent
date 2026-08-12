package com.minikun.tokenbudget.recovery;

public interface ContextRecoveryPriorityPolicy {
    RecoveryPriorityPlan prioritize(ContextRecoveryPlan plan);
}