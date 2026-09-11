package com.minikun.pcs;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.Prompt;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.PromptRole;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;
import com.minikun.personality.model.PersonalUserModel;
import com.minikun.personality.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptComposerTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void composesSectionsInFixedOrder() {
        Prompt prompt = new PromptComposer().compose(request());
        assertEquals(2, prompt.messages().size());
        assertEquals(PromptRole.SYSTEM, prompt.messages().get(0).role());
        assertEquals(PromptRole.USER, prompt.messages().get(1).role());

        String system = prompt.messages().get(0).content();
            assertTrue(system.indexOf("[Character]") < system.indexOf("[Runtime]"));
        assertTrue(system.indexOf("[Runtime]") < system.indexOf("[Conversation]"));
        assertTrue(system.indexOf("[Conversation]") < system.indexOf("[Knowledge]"));
        assertTrue(system.indexOf("[Knowledge]") < system.indexOf("[Capabilities]"));
            assertTrue(system.indexOf("[Runtime]") < system.indexOf("[Knowledge]"));
        assertEquals("Answer this", prompt.messages().get(1).content());
    }

    @Test
    void includesGoalFeasibilityRulesInEverySystemPrompt() {
        String system = new PromptComposer().compose(request()).messages().getFirst().content();

        assertTrue(system.contains("complete requested end state"));
        assertTrue(system.contains("Reject any option that cannot produce the complete end state"));
        assertTrue(system.contains("cycling there without the car is infeasible"));
    }

    @Test
    void preservesRecentConversationRolesBetweenSystemAndCurrentUser() {
        ConversationContext conversation = new ConversationContext(
                "user: ก่อนหน้า\n\nassistant: คำตอบเดิม",
                "Rolling summary",
                List.of(
                        new PromptMessage(PromptRole.USER, "ก่อนหน้า"),
                        new PromptMessage(PromptRole.ASSISTANT, "คำตอบเดิม")));
        Prompt prompt = new PromptComposer().compose(new PromptRequest(
                character(), new RuntimeContext("now"), conversation, null,
                List.of(), new UserMessage("ถามต่อ")));

        assertEquals(List.of(
                PromptRole.SYSTEM, PromptRole.USER, PromptRole.ASSISTANT, PromptRole.USER),
                prompt.messages().stream().map(PromptMessage::role).toList());
        assertTrue(prompt.messages().getFirst().content().contains("Rolling summary"));
        assertFalse(prompt.messages().getFirst().content().contains("คำตอบเดิม"));
        assertEquals("คำตอบเดิม", prompt.messages().get(2).content());
    }

    @Test
    void rendersPersonalUserModelAsSeparateBackgroundContext() {
        PersonalUserModel userModel = new PersonalUserModel("default",
                new UserProfile("default", "พี่", "th", "concise", "Asia/Bangkok"),
                List.of(), List.of(), java.time.Instant.now());
        PromptRequest request = new PromptRequest(character(), new RuntimeContext("now"), null, null,
                List.of(), new UserMessage("hello"), SearchSelectionSignals.EMPTY, SearchContext.EMPTY,
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY, null,
                com.minikun.personality.signal.PersonaSelectionSignals.EMPTY, userModel);

        String system = new PromptComposer().compose(request).messages().getFirst().content();

        assertTrue(system.contains("[Personal User Context]"));
        assertTrue(system.contains("display_name: พี่"));
    }

    @Test
    void omitsEmptyOptionalSections() {
        PromptRequest request = new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello"));

        Prompt prompt = new PromptComposer().compose(request);
        String system = prompt.messages().get(0).content();

        assertTrue(!system.contains("[Conversation]"));
        assertTrue(!system.contains("[Knowledge]"));
        assertTrue(!system.contains("[Capabilities]"));
    }

    @Test
    void rejectsInvalidRequiredInputs() {
        assertThrows(PromptException.class, () -> new PromptComposer().compose(null));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(null, new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello"))));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(character(), new RuntimeContext(" "), null, null, List.of(), new UserMessage("hello"))));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(character(), new RuntimeContext("now"), null, null, List.of(), new UserMessage(" "))));
    }

    @Test
    void requestCopiesCapabilityListAndOutputIsDeterministic() {
        PromptRequest request = request();
        Prompt first = new PromptComposer().compose(request);
        Prompt second = new PromptComposer().compose(request);

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class,
            () -> first.messages().add(first.messages().get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> request.capabilities().add(new CapabilityInstruction("later", "instruction")));
    }

    @Test
    void composeDelegatesToTheDiagnosticsCompositionResult() {
        PromptComposer composer = new PromptComposer();

        assertEquals(composer.compose(request()), composer.composeWithDiagnostics(request()).prompt());
    }

    @Test
    void composerSuppliesCanonicalEmptyMemorySelectionSignals() {
        AtomicReference<McsSelectionContext> capturedContext = new AtomicReference<>();
        CharacterSpecification character = characterWithPolicies(Map.of("identity", LoadingPolicy.DYNAMIC));
        PromptComposer composer = new PromptComposer(new McsSelector(Map.of(
                "identity", (module, context) -> {
                    capturedContext.set(context);
                    return McsSelectionDecision.selected("test", "selected");
                })));

        composer.compose(new PromptRequest(character, new RuntimeContext("now"), null, null,
                List.of(), new UserMessage("hello")));

        assertSame(MemorySelectionSignals.EMPTY, capturedContext.get().memorySelectionSignals());
    }

    @Test
        void nonInterestCompatibilitySelectionPreservesPromptWhenPoliciesBecomeDynamic() {
        Map<String, LoadingPolicy> policies = character().loadingPolicies().keySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                name -> name, name -> LoadingPolicy.DYNAMIC,
                (left, right) -> left, LinkedHashMap::new));
        policies.put("interests", LoadingPolicy.ALWAYS);
        CharacterSpecification dynamicCharacter = characterWithPolicies(
            policies);
        PromptRequest dynamicRequest = new PromptRequest(dynamicCharacter, new RuntimeContext("2026-08-01"),
                new ConversationContext("Previous turn"), new KnowledgeContext("Retrieved fact"),
                List.of(new CapabilityInstruction("search", "Use retrieved sources")),
                new UserMessage("Answer this"));

        PromptRequest baselineRequest = new PromptRequest(
            characterWithPolicies(Map.of("interests", LoadingPolicy.ALWAYS)),
            new RuntimeContext("2026-08-01"), new ConversationContext("Previous turn"),
            new KnowledgeContext("Retrieved fact"),
            List.of(new CapabilityInstruction("search", "Use retrieved sources")),
            new UserMessage("Answer this"));
        assertEquals(new PromptComposer().compose(baselineRequest), new PromptComposer().compose(dynamicRequest));
    }

        @Test
        void compositionRendersOnlySelectedModulesAndExposesMatchingDiagnostics() {
        CharacterSpecification character = characterWithPolicies(Map.of(
            "identity", LoadingPolicy.DYNAMIC,
            "interests", LoadingPolicy.ALWAYS));
        PromptComposer composer = new PromptComposer(new McsSelector(Map.of(
            "identity", (module, context) -> McsSelectionDecision.skipped("test", "Strategy not matched"))));

        PromptCompositionResult result = composer.composeWithDiagnostics(new PromptRequest(
            character, new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello")));
        String system = result.prompt().messages().get(0).content();

        assertTrue(!system.contains("\nidentity:"));
        assertTrue(system.contains("\npersonality:"));
        assertEquals(9, result.selectionDiagnostics().size());
        assertFalse(result.selectionDiagnostics().get(0).selected());
        assertTrue(result.selectionDiagnostics().stream().skip(1).allMatch(McsSelectionDiagnostic::selected));
        assertEquals(result.selectionDiagnostics().stream().map(McsSelectionDiagnostic::module).toList(),
            result.selectionDecisions().stream().map(decision -> decision.module().name()).toList());
        }

    @Test
    void interestsIsRenderedWhenConfiguredMetadataMatches() {
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(),
                new UserMessage("Tell me about programming")));

        String system = result.prompt().messages().get(0).content();
        assertTrue(system.contains("\ninterests:"));
        assertTrue(result.selectionDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.module().equals("interests") && diagnostic.selected()));
    }

    @Test
    void interestsIsOmittedWhenConfiguredMetadataDoesNotMatch() {
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(),
                new UserMessage("Tell me about cooking")));

        String system = result.prompt().messages().get(0).content();
        assertTrue(!system.contains("\ninterests:"));
        assertTrue(result.selectionDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.module().equals("interests") && !diagnostic.selected()));
        assertTrue(system.contains("\nidentity:"));
        assertTrue(result.selectionDiagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.module().equals("catchphrases") && !diagnostic.selected()));
        assertTrue(!system.contains("\ncatchphrases:"));
    }

    @Test
    void catchphrasesIsRenderedWhenConfiguredMetadataMatches() {
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(),
                new UserMessage("Let's plan this")));
        String system = result.prompt().messages().get(0).content();

        assertTrue(system.contains("\ncatchphrases:"));
        assertTrue(result.selectionDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.module().equals("catchphrases") && diagnostic.selected()));
    }

        @Test
        void processesAssembledContextWithoutChangingSectionOrderOrUserMessage() {
        PromptRequest request = requestWithBudget(
            new RuntimeContext(" now   "), new KnowledgeContext("knowledge"),
            new UserMessage("User  message"), completeBudget(100_000));

        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(request);

        assertTrue(result.contextProcessingResult().isPresent());
        assertEquals(Set.of(
            ContextBudgetSection.CHARACTER,
            ContextBudgetSection.RUNTIME,
            ContextBudgetSection.KNOWLEDGE,
            ContextBudgetSection.USER_MESSAGE),
            result.contextProcessingResult().orElseThrow().selectedItems().stream()
                .map(ContextItem::section).collect(java.util.stream.Collectors.toSet()));
        String system = result.prompt().messages().get(0).content();
        assertTrue(system.contains("[Character]"));
        assertTrue(system.contains("[Runtime]"));
        assertTrue(system.contains("[Knowledge]"));
        assertTrue(system.indexOf("[Character]") < system.indexOf("[Runtime]"));
        assertTrue(system.indexOf("[Runtime]") < system.indexOf("[Knowledge]"));
        assertEquals("User  message", result.prompt().messages().get(1).content());
        assertTrue(system.contains("[Runtime]\n now"));
        }

        @Test
        void optionalKnowledgeCanBeEvictedWhileRequiredContextRemainsVisible() {
        ContextBudget budget = budgetWithAllocations(100_000, 100_000, 100_000, 100_000, 1, 100_000, 100_000);
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(requestWithBudget(
            new RuntimeContext("now"), new KnowledgeContext("knowledge that does not fit"),
            new UserMessage("hello"), budget));

        ContextProcessingResult processing = result.contextProcessingResult().orElseThrow();
        assertFalse(processing.diagnostics().requiredOverflow());
        assertTrue(processing.evictions().stream()
            .anyMatch(eviction -> eviction.item().section() == ContextBudgetSection.KNOWLEDGE));
        assertTrue(!result.prompt().messages().get(0).content().contains("[Knowledge]"));
        assertEquals("hello", result.prompt().messages().get(1).content());
        }

    @Test
    void keepsRenderedBrowserEvidenceWhenKnowledgeBudgetIsTooSmall() {
        KnowledgeCandidate browser = new KnowledgeCandidate(
                "browser-0", KnowledgeSource.BROWSER,
                "Source URL: https://www.facebook.com/share/p/test\n"
                        + "Rendered page content:\nactual post ".repeat(500),
                0, "https://www.facebook.com/share/p/test");
        ContextBudget budget = budgetWithAllocations(
                100_000, 100_000, 100_000, 100_000, 1, 100_000, 100_000);
        PromptRequest request = new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(),
                new UserMessage("summarize this post"), SearchSelectionSignals.EMPTY,
                SearchContext.EMPTY, new KnowledgeSelection(List.of(browser), false),
                KnowledgeConsolidation.EMPTY, budget);

        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(request);

        assertTrue(result.contextProcessingResult().orElseThrow().diagnostics().requiredOverflow());
        assertTrue(result.prompt().messages().getFirst().content().contains("actual post"));
    }

        @Test
        void oversizedConversationIsWindowedToRecentTurnsBeforeSelection() {
        String conversation = "user: oldest-" + "ก".repeat(180)
                + "\n\nassistant: older-answer-" + "ข".repeat(180)
                + "\n\nuser: recent-question"
                + "\n\nassistant: latest-answer";
        ContextBudget budget = budgetWithAllocations(
                100_000, 100_000, 120, 100_000, 100_000, 100_000, 100_000);
        PromptRequest request = new PromptRequest(
                character(), new RuntimeContext("now"), new ConversationContext(conversation), null,
                List.of(), new UserMessage("continue"), SearchSelectionSignals.EMPTY,
                SearchContext.EMPTY, KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY, budget);

        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(request);

        String system = result.prompt().messages().getFirst().content();
        assertTrue(system.contains("[Conversation]"));
        assertTrue(system.contains("recent-question"));
        assertTrue(system.contains("latest-answer"));
        assertTrue(system.contains("Earlier conversation omitted"));
        assertFalse(system.contains("oldest-"));
        assertTrue(result.contextProcessingResult().orElseThrow().evictions().stream()
                .noneMatch(eviction -> eviction.item().section() == ContextBudgetSection.CONVERSATION));
        }

        @Test
    void requiredOverflowRemainsVisibleAndRequiredSectionsAreNotDropped() {
        ContextBudget budget = budgetWithAllocations(1, 100_000, 100_000, 100_000, 100_000, 100_000, 100_000);
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(requestWithBudget(
            new RuntimeContext("now"), null, new UserMessage("hello"), budget));

        ContextProcessingResult processing = result.contextProcessingResult().orElseThrow();
        assertTrue(processing.diagnostics().requiredOverflow());
        assertTrue(result.prompt().messages().get(0).content().contains("[Character]"));
        assertEquals("hello", result.prompt().messages().get(1).content());
        }

    @Test
    void requiredCapabilitySurvivesCharacterOverflow() {
        ContextBudget budget = budgetWithAllocations(1, 100_000, 100_000, 100_000, 100_000, 1, 100_000);
        PromptCompositionResult result = new PromptComposer().composeWithDiagnostics(new PromptRequest(
                character(), new RuntimeContext("now"), null, null,
                List.of(new CapabilityInstruction("Verified tool result", "weather facts", true)),
                new UserMessage("hello"), SearchSelectionSignals.EMPTY, SearchContext.EMPTY,
                KnowledgeSelection.EMPTY, KnowledgeConsolidation.EMPTY, budget,
                com.minikun.personality.signal.PersonaSelectionSignals.EMPTY));

        assertTrue(result.contextProcessingResult().orElseThrow().selectedItems().stream()
                .anyMatch(item -> item.section() == ContextBudgetSection.CAPABILITIES
                        && item.required() && item.content().contains("weather facts")));
        assertTrue(result.prompt().messages().get(0).content().contains("Verified tool result"));
    }

    private PromptRequest request() {
        return new PromptRequest(character(), new RuntimeContext("2026-08-01"),
            new ConversationContext("Previous turn"), new KnowledgeContext("Retrieved fact"),
                List.of(new CapabilityInstruction("search", "Use retrieved sources")),
                new UserMessage("Answer this"));
    }

    private PromptRequest requestWithBudget(
            RuntimeContext runtime,
            KnowledgeContext knowledge,
            UserMessage userMessage,
            ContextBudget budget) {
        return new PromptRequest(character(), runtime, null, knowledge, List.of(), userMessage,
                SearchSelectionSignals.EMPTY, SearchContext.EMPTY, KnowledgeSelection.EMPTY,
                KnowledgeConsolidation.EMPTY, budget);
    }

    private ContextBudget completeBudget(long amount) {
        return budgetWithAllocations(amount, amount, amount, amount, amount, amount, amount);
    }

    private ContextBudget budgetWithAllocations(
            long character, long runtime, long conversation, long memory,
            long knowledge, long capabilities, long userMessage) {
        return new ContextBudget(ContextBudgetUnit.CHARACTERS,
                character + runtime + conversation + memory + knowledge + capabilities + userMessage,
                List.of(
                        new ContextBudgetAllocation(ContextBudgetSection.CHARACTER, character),
                        new ContextBudgetAllocation(ContextBudgetSection.RUNTIME, runtime),
                        new ContextBudgetAllocation(ContextBudgetSection.CONVERSATION, conversation),
                        new ContextBudgetAllocation(ContextBudgetSection.MEMORY, memory),
                        new ContextBudgetAllocation(ContextBudgetSection.KNOWLEDGE, knowledge),
                        new ContextBudgetAllocation(ContextBudgetSection.CAPABILITIES, capabilities),
                        new ContextBudgetAllocation(ContextBudgetSection.USER_MESSAGE, userMessage)));
    }

    private CharacterSpecification character() {
        return new CharacterLoader(MCS_ROOT).load();
    }

    private CharacterSpecification characterWithPolicies(Map<String, LoadingPolicy> overrides) {
        CharacterSpecification source = character();
        Map<String, LoadingPolicy> policies = new LinkedHashMap<>(source.loadingPolicies());
        policies.putAll(overrides);
        return new CharacterSpecification(source.name(), source.version(), source.description(),
                source.primaryLanguage(), source.fallbackLanguage(), source.role(), source.relationship(),
                source.defaultMode(), source.metadata(), policies, source.selectionMetadata(), source.identity(), source.personality(),
                source.values(), source.communication(), source.behavior(), source.reasoning(), source.interests(),
                source.boundaries(), source.catchphrases());
    }
}
