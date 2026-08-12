package com.minikun.tokenbudget.pressure;

public interface ContextPressureRecoveryPolicy {
    RecoveryAction decide(ContextPressureDecision decision);
}