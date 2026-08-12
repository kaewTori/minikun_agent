package com.minikun.tokenbudget.recovery;

public interface RecoveryExecutor {
    RecoveryExecutionResult execute(RecoveryPriorityPlan plan);
}