package com.minikun.tokenbudget.planner;

import com.minikun.model.ChatModelId;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.counter.TokenCounter;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.policy.TokenBudgetPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicTokenPlannerTest {
    private static final ModelCapability CAPABILITY =
            new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, 8192, 4096);
    private static final TokenBudget BUDGET = new TokenBudget(4096, 512, 2048);

    @Test
    void plansNormalPromptUsingCountedTokensAndProvidedBudget() {
        TokenCounter counter = prompt -> 1000;
        AtomicReference<TokenBudget> receivedBudget = new AtomicReference<>();
        AtomicReference<Long> receivedInputTokens = new AtomicReference<>();
        TokenBudgetPolicy policy = (budget, inputTokens) -> {
            receivedBudget.set(budget);
            receivedInputTokens.set(inputTokens);
            return new TokenBudgetAllocation(inputTokens, 2048, false);
        };

        TokenBudgetAllocation allocation = new DefaultDynamicTokenPlanner(counter, policy)
                .plan(CAPABILITY, BUDGET, "prompt");

        assertEquals(2048, allocation.availableOutputTokens());
        assertFalse(allocation.truncated());
        assertSame(BUDGET, receivedBudget.get());
        assertEquals(1000, receivedInputTokens.get());
    }

    @Test
    void delegatesContextExhaustionToPolicy() {
        TokenCounter counter = prompt -> 4000;
        TokenBudgetPolicy policy = (budget, inputTokens) ->
                new TokenBudgetAllocation(inputTokens, 0, true);

        TokenBudgetAllocation allocation = new DefaultDynamicTokenPlanner(counter, policy)
                .plan(CAPABILITY, BUDGET, "prompt");

        assertEquals(0, allocation.availableOutputTokens());
        assertTrue(allocation.truncated());
    }

    @Test
    void acceptsDifferentCapabilityWithoutChangingIt() {
        ModelCapability capability = new ModelCapability(
                ChatModelId.EXISTING, ModelRole.TASK, 2048, 512);
        DynamicTokenPlanner planner = new DefaultDynamicTokenPlanner(
                prompt -> 10, (budget, inputTokens) ->
                new TokenBudgetAllocation(inputTokens, 100, false));

        TokenBudgetAllocation allocation = planner.plan(capability, BUDGET, "prompt");

        assertEquals(100, allocation.availableOutputTokens());
        assertEquals(ChatModelId.EXISTING, capability.modelId());
        assertEquals(ModelRole.TASK, capability.role());
        assertEquals(2048, capability.contextWindowTokens());
        assertEquals(512, capability.maxOutputTokens());
    }

    @Test
    void rejectsNullPlanningInputs() {
        DynamicTokenPlanner planner = new DefaultDynamicTokenPlanner(
                prompt -> 10, (budget, inputTokens) ->
                new TokenBudgetAllocation(inputTokens, 100, false));

        assertThrows(NullPointerException.class, () -> planner.plan(null, BUDGET, "prompt"));
        assertThrows(NullPointerException.class, () -> planner.plan(CAPABILITY, null, "prompt"));
        assertThrows(NullPointerException.class, () -> planner.plan(CAPABILITY, BUDGET, null));
    }

    @Test
    void rejectsNullDependencies() {
        assertThrows(NullPointerException.class,
                () -> new DefaultDynamicTokenPlanner(null, (budget, inputTokens) ->
                        new TokenBudgetAllocation(inputTokens, 100, false)));
        assertThrows(NullPointerException.class,
                () -> new DefaultDynamicTokenPlanner(prompt -> 10, null));
    }
}
