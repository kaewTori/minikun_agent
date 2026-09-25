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

    @Test
    void reservesCreativeOutputBeforeComposingLongRolePreservingHistory() {
        var factory = new DynamicGenerationOptionsFactory(
                new com.minikun.tokenbudget.planner.DefaultDynamicTokenPlanner(
                        new com.minikun.tokenbudget.counter.ApproximateTokenCounter(),
                        new com.minikun.tokenbudget.policy.DefaultTokenBudgetPolicy()),
                new DefaultGenerationOptionsResolver());
        var history = new java.util.ArrayList<com.minikun.pcs.model.PromptMessage>();
        for (int i = 0; i < 80; i++) {
            history.add(new com.minikun.pcs.model.PromptMessage(
                    i % 2 == 0 ? com.minikun.pcs.model.PromptRole.USER
                            : com.minikun.pcs.model.PromptRole.ASSISTANT,
                    "ฉากที่ " + i + " เรื่องราวในป่าเวทมนตร์".repeat(100)));
        }
        PromptRequest base = request();
        var story = new PromptRequest(base.character(), base.runtime(),
                new com.minikun.pcs.model.ConversationContext("story", "", history),
                null, List.of(), new UserMessage("แต่งต่อจากฉากล่าสุด"));
        for (int window : new int[] {16_384, 32_768}) {
            var result = runtime(factory).prepare(story,
                    new GenerationOptions(0.7, 4_096, List.of()),
                    new ModelCapability(ChatModelId.EXISTING, ModelRole.CHAT, window, 4_096),
                    true, 200_000, 256, 4_096);
            assertEquals(4_096, result.generationOptions().maxTokens());
            assertEquals(1, result.snapshot().recoveryAttempts());
            assertTrue(result.snapshot().estimatedInputTokens() + 4_096 + 256 <= window);
            var messages = result.prompt().messages();
            assertEquals(history.getLast(), messages.get(messages.size() - 2));
            assertEquals(story.userMessage().content(), messages.getLast().content());
            assertTrue(messages.size() < history.size() + 2);
        }
    }

    @Test
    void recovers1076TokenAllocationOnceForCreativeRequest() {
        AtomicInteger calls = new AtomicInteger();
        var factory = new DynamicGenerationOptionsFactory((capability, budget, prompt) ->
                new TokenBudgetAllocation(15_052, calls.incrementAndGet() == 1 ? 1_076 : 4_096, false),
                new DefaultGenerationOptionsResolver());
        var result = runtime(factory).prepare(request(), new GenerationOptions(0.7, 4_096, List.of()),
                CAPABILITY, true, 40_000, 256, 4_096);
        assertEquals(2, calls.get());
        assertEquals(1, result.snapshot().recoveryAttempts());
        assertEquals(4_096, result.generationOptions().maxTokens());
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
