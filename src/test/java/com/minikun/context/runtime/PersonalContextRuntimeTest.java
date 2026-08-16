package com.minikun.context.runtime;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.model.GenerationOptions;
import com.minikun.model.ChatModelId;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.DefaultGenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalContextRuntimeTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");
    private static final ModelCapability CAPABILITY = new ModelCapability(
            ChatModelId.EXISTING, ModelRole.CHAT, 16_384, 4_096);

    @Test
    void preparesPromptWithoutInvokingDynamicBudgetWhenDisabled() {
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> {
                    throw new AssertionError("dynamic planner must not run");
                }, new DefaultGenerationOptionsResolver());

        PersonalContextRuntimeResult result = runtime(factory).prepare(
                request(), new GenerationOptions(0.7, 512, List.of()), CAPABILITY,
                false, 24_000, 256, 2_048);

        assertNotNull(result.prompt());
        assertEquals(512, result.generationOptions().maxTokens());
        assertTrue(result.budgetResult().isEmpty());
    }

    @Test
    void retriesCompositionOnceWhenContextPressureRequiresRecovery() {
        AtomicInteger plannerCalls = new AtomicInteger();
        DynamicTokenPlanner planner = (capability, budget, prompt) -> {
            plannerCalls.incrementAndGet();
            return new TokenBudgetAllocation(16_000, 100, true);
        };
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                planner, new DefaultGenerationOptionsResolver());

        PersonalContextRuntimeResult result = runtime(factory).prepare(
                request(), new GenerationOptions(0.7, 512, List.of()), CAPABILITY,
                true, 24_000, 256, 2_048);

        assertEquals(2, plannerCalls.get());
        assertEquals(100, result.generationOptions().maxTokens());
        assertTrue(result.budgetResult().isPresent());
    }

    private PersonalContextRuntime runtime(DynamicGenerationOptionsFactory factory) {
        return new PersonalContextRuntime(new PromptComposer(), factory);
    }

    private PromptRequest request() {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        return new PromptRequest(
                character,
                new RuntimeContext("today"),
                null,
                null,
                List.of(),
                new UserMessage("hello"));
    }
}
