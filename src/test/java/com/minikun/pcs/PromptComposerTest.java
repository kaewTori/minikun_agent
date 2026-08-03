package com.minikun.pcs;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.Prompt;
import com.minikun.pcs.model.PromptRole;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("Answer this", prompt.messages().get(1).content());
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
        assertTrue(system.contains("\ncatchphrases:"));
    }

    private PromptRequest request() {
        return new PromptRequest(character(), new RuntimeContext("2026-08-01"),
            new ConversationContext("Previous turn"), new KnowledgeContext("Retrieved fact"),
                List.of(new CapabilityInstruction("search", "Use retrieved sources")),
                new UserMessage("Answer this"));
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