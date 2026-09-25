package com.minikun.context.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.context.model.ContextRuntimeSnapshot;
import com.minikun.context.model.ContextSource;
import com.minikun.context.recovery.ContextRecoveryDecision;
import com.minikun.context.recovery.ContextRecoveryPolicy;
import com.minikun.context.recovery.DefaultContextRecoveryPolicy;
import com.minikun.pcs.ContextBudget;
import com.minikun.pcs.ContextBudgetPolicy;
import com.minikun.pcs.DefaultContextBudgetPolicy;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.model.Prompt;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates context composition, token budgeting, and one bounded recovery pass
 * for a single-user personal agent request.
 */
public final class PersonalContextRuntime {
    private final PromptComposer promptComposer;
    private final ContextBudgetPolicy contextBudgetPolicy;
    private final DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory;
    private final ContextRecoveryPolicy contextRecoveryPolicy;
    private final MeterRegistry meterRegistry;

    @Autowired
    public PersonalContextRuntime(
            PromptComposer promptComposer,
            DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory,
            MeterRegistry meterRegistry,
            ContextRecoveryPolicy contextRecoveryPolicy) {
        this(promptComposer, dynamicGenerationOptionsFactory, Objects.requireNonNull(meterRegistry,
                "meter registry must not be null"), contextRecoveryPolicy, true);
    }

    public PersonalContextRuntime(
            PromptComposer promptComposer,
            DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory) {
        this(promptComposer, dynamicGenerationOptionsFactory, null,
                new DefaultContextRecoveryPolicy(), true);
    }

    private PersonalContextRuntime(
            PromptComposer promptComposer,
            DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory,
            MeterRegistry meterRegistry,
            ContextRecoveryPolicy contextRecoveryPolicy,
            boolean ignored) {
        this.promptComposer = Objects.requireNonNull(promptComposer, "prompt composer must not be null");
        this.contextBudgetPolicy = new DefaultContextBudgetPolicy();
        this.dynamicGenerationOptionsFactory = Objects.requireNonNull(
                dynamicGenerationOptionsFactory, "dynamic generation options factory must not be null");
        this.contextRecoveryPolicy = Objects.requireNonNull(
                contextRecoveryPolicy, "context recovery policy must not be null");
        this.meterRegistry = meterRegistry;
    }

    public PersonalContextRuntimeResult prepare(
            PromptRequest request,
            GenerationOptions requestedOptions,
            ModelCapability capability,
            boolean dynamicTokenBudgetEnabled,
            long contextBudgetCharacters,
            long reservedOutputTokens,
            int configuredGenerationMaxTokens) {
        Objects.requireNonNull(request, "prompt request must not be null");
        Objects.requireNonNull(requestedOptions, "requested generation options must not be null");
        if (dynamicTokenBudgetEnabled) {
            Objects.requireNonNull(capability, "model capability is required when dynamic budget is enabled");
        }
        if (contextBudgetCharacters < 0) {
            throw new IllegalArgumentException("context budget characters must not be negative");
        }
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reserved output tokens must not be negative");
        }
        if (configuredGenerationMaxTokens < 0) {
            throw new IllegalArgumentException("configured generation max tokens must not be negative");
        }

        Timer.Sample timer = meterRegistry == null ? null : Timer.start(meterRegistry);
        try {
            long effectiveContextCharacters = contextBudgetCharacters;
            long maximumInputTokens = 0;
            if (dynamicTokenBudgetEnabled) {
                TokenBudget tokenBudget = new TokenBudget(capability.contextWindowTokens(),
                        reservedOutputTokens, configuredGenerationMaxTokens);
                long desiredOutput = dynamicGenerationOptionsFactory.desiredOutputTokens(
                        requestedOptions, capability, tokenBudget);
                maximumInputTokens = Math.max(0, capability.contextWindowTokens()
                        - Math.min(reservedOutputTokens, capability.contextWindowTokens()) - desiredOutput);
                // The measured prompt drives recovery when this ASCII pre-cap is too generous.
                effectiveContextCharacters = Math.min(contextBudgetCharacters,
                        maximumInputTokens > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : maximumInputTokens * 4);
            }
            PromptRequest budgetedRequest = withContextBudget(request, effectiveContextCharacters);
            Prompt prompt = promptComposer.compose(budgetedRequest);
            recordPrompt(prompt);
            if (!dynamicTokenBudgetEnabled) {
                return new PersonalContextRuntimeResult(
                        prompt, requestedOptions, Optional.empty(), snapshot(
                                request, contextBudgetCharacters, prompt, 0, null));
            }

            DynamicGenerationOptionsResult budgetResult = budget(
                    prompt, requestedOptions, capability, reservedOutputTokens, configuredGenerationMaxTokens);
            int recoveryAttempts = 0;
            if (requiresRecovery(budgetResult)) {
                ContextRecoveryDecision recovery = contextRecoveryPolicy.decide(
                        effectiveContextCharacters,
                        budgetResult.pressureLevel());
                if (recovery.required()) {
                    recoveryAttempts++;
                    recordRecovery();
                    long measuredInputTokens = budgetResult.tokenBudgetDecision().inputTokens();
                    long proportionalTarget = measuredInputTokens == 0
                            ? recovery.targetContextCharacters()
                            : (long) (effectiveContextCharacters
                                    * Math.min(1.0, (double) maximumInputTokens / measuredInputTokens));
                    budgetedRequest = withContextBudget(request,
                            Math.min(recovery.targetContextCharacters(), proportionalTarget));
                    prompt = promptComposer.compose(budgetedRequest);
                    recordPrompt(prompt);
                    budgetResult = budget(
                            prompt, requestedOptions, capability, reservedOutputTokens, configuredGenerationMaxTokens);
                }
            }
            return new PersonalContextRuntimeResult(
                    prompt,
                    budgetResult.generationOptions(),
                    Optional.of(budgetResult),
                    snapshot(request, contextBudgetCharacters, prompt, recoveryAttempts, budgetResult));
        } finally {
            if (timer != null) {
                timer.stop(Timer.builder("minikun.context.runtime.prepare").register(meterRegistry));
            }
        }
    }

    private void recordRecovery() {
        if (meterRegistry != null) {
            Counter.builder("minikun.context.runtime.recovery")
                    .tag("action", "reduce_context_and_recompose")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void recordPrompt(Prompt prompt) {
        if (meterRegistry != null) {
            long characters = prompt.messages().stream()
                    .mapToLong(message -> message.content().length())
                    .sum();
            meterRegistry.summary("minikun.context.runtime.prompt.characters").record(characters);
        }
    }

    private ContextRuntimeSnapshot snapshot(
            PromptRequest request,
            long requestedContextCharacters,
            Prompt prompt,
            int recoveryAttempts,
            DynamicGenerationOptionsResult budgetResult) {
        long promptCharacters = prompt.messages().stream()
                .mapToLong(message -> message.content().length())
                .sum();
        return new ContextRuntimeSnapshot(
                requestedContextCharacters,
                promptCharacters,
                recoveryAttempts,
                budgetResult == null ? null : budgetResult.tokenBudgetDecision().inputTokens(),
                budgetResult == null ? null : budgetResult.tokenBudgetDecision().allocatedOutputTokens(),
                sourceCharacters(request));
    }

    private java.util.Map<ContextSource, Long> sourceCharacters(PromptRequest request) {
        java.util.EnumMap<ContextSource, Long> values = new java.util.EnumMap<>(ContextSource.class);
        values.put(ContextSource.RUNTIME, (long) request.runtime().content().length());
        values.put(ContextSource.USER_MESSAGE, (long) request.userMessage().content().length());
        if (request.conversation() != null) {
            values.put(ContextSource.CONVERSATION, (long) request.conversation().content().length());
        }
        if (request.knowledge() != null) {
            values.put(ContextSource.MEMORY, (long) request.knowledge().content().length());
        }
        long capabilityCharacters = request.capabilities().stream()
                .filter(java.util.Objects::nonNull)
                .mapToLong(capability -> capability.content() == null ? 0 : capability.content().length())
                .sum();
        if (capabilityCharacters > 0) {
            values.put(ContextSource.TOOLS, capabilityCharacters);
        }
        return values;
    }

    private DynamicGenerationOptionsResult budget(
            Prompt prompt,
            GenerationOptions requestedOptions,
            ModelCapability capability,
            long reservedOutputTokens,
            int configuredGenerationMaxTokens) {
        TokenBudget budget = new TokenBudget(
                capability.contextWindowTokens(), reservedOutputTokens, configuredGenerationMaxTokens);
        String promptText = String.join("\n", prompt.messages().stream()
                .map(message -> message.content())
                .toList());
        return dynamicGenerationOptionsFactory.createWithDiagnostics(
                requestedOptions, capability, budget, promptText);
    }

    private boolean requiresRecovery(DynamicGenerationOptionsResult result) {
        return result.pressureLevel() != ContextPressureLevel.NORMAL;
    }

    private PromptRequest withContextBudget(PromptRequest request, long characters) {
        ContextBudget budget = contextBudgetPolicy.allocate(characters);
        return new PromptRequest(
                request.character(),
                request.runtime(),
                request.conversation(),
                request.knowledge(),
                request.capabilities(),
                request.userMessage(),
                request.searchSelectionSignals(),
                request.searchContext(),
                request.knowledgeSelection(),
                request.knowledgeConsolidation(),
                budget,
                request.personaSelectionSignals(),
                request.personalUserModel());
    }
}
