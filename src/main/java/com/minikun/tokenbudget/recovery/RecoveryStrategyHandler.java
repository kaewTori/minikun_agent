package com.minikun.tokenbudget.recovery;

public interface RecoveryStrategyHandler {
    RecoveryExecutionResult execute(RecoveryStep step);
}