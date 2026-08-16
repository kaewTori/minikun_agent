package com.minikun.context.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureLevel;

public interface ContextRecoveryPolicy {
    ContextRecoveryDecision decide(long currentContextCharacters, ContextPressureLevel pressureLevel);
}
