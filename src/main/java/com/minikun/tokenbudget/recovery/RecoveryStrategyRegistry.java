package com.minikun.tokenbudget.recovery;

public interface RecoveryStrategyRegistry {
    RecoveryStrategyHandler resolve(RecoveryStep step);
}