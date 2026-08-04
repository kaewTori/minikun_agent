package com.minikun.pcs;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.character.model.LoadingPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McsSelectorTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void alwaysModulesAreSelectedAndDynamicModulesAreEvaluatedIndependently() {
        CharacterSpecification character = characterWithPolicies(Map.of(
                "identity", LoadingPolicy.ALWAYS,
                "personality", LoadingPolicy.DYNAMIC,
                "interests", LoadingPolicy.ALWAYS));
        McsSelector selector = new McsSelector(Map.of(
                "personality", (module, context) -> McsSelectionDecision.selected("test", "Strategy matched")));

        McsSelectionResult result = selector.select(character, new McsSelectionContext("hello", "history"));

        assertEquals(List.of("identity", "personality", "values", "communication", "behavior",
                "reasoning", "interests", "boundaries", "catchphrases"),
                result.selectedModules().stream().map(McsModule::name).toList());
        assertEquals(9, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().allMatch(McsSelectionDiagnostic::selected));
        assertEquals("ALWAYS", result.diagnostics().get(0).reason());
        assertEquals("test", result.diagnostics().get(1).selector());
    }

    @Test
    void dynamicModulesCanBeSkippedWithIndependentDiagnostics() {
        CharacterSpecification character = characterWithPolicies(Map.of(
                "identity", LoadingPolicy.DYNAMIC,
                "personality", LoadingPolicy.DYNAMIC));
        McsSelector selector = new McsSelector(Map.of(
                "identity", (module, context) -> McsSelectionDecision.skipped("identity-rule", "Strategy not matched"),
                "personality", (module, context) -> McsSelectionDecision.selected("personality-rule", "Strategy matched")));

        McsSelectionResult result = selector.select(character, new McsSelectionContext("hello", "history"));

        assertFalse(result.selectedModules().stream().anyMatch(module -> module.name().equals("identity")));
        assertTrue(result.selectedModules().stream().anyMatch(module -> module.name().equals("personality")));
        assertEquals("Strategy not matched", result.diagnostics().get(0).reason());
        assertEquals("personality-rule", result.diagnostics().get(1).selector());
    }

    @Test
    void identicalContextsProduceIdenticalDecisions() {
        CharacterSpecification character = characterWithPolicies(Map.of("identity", LoadingPolicy.DYNAMIC));
        McsSelector selector = new McsSelector(Map.of("identity", new ModuleNameMentionStrategy()));
        McsSelectionContext context = new McsSelectionContext("Discuss identity", "Earlier context");

        assertEquals(selector.select(character, context), selector.select(character,
                new McsSelectionContext("Discuss identity", "Earlier context")));
    }

    @Test
    void modulesAndDecisionsFollowConfiguredManifestOrder() {
                CharacterSpecification character = characterWithPolicies(Map.of("interests", LoadingPolicy.ALWAYS));

        McsSelectionResult result = new McsSelector().select(character,
                new McsSelectionContext("hello", "history"));
        List<String> configuredOrder = List.copyOf(character.loadingPolicies().keySet());

        assertEquals(configuredOrder, result.decisions().stream()
                .map(decision -> decision.module().name()).toList());
        assertEquals(configuredOrder, result.selectedModules().stream()
                .map(McsModule::name).toList());
        assertEquals(configuredOrder, result.diagnostics().stream()
                .map(McsSelectionDiagnostic::module).toList());
    }

    @Test
    void defaultDynamicStrategySelectsEveryModuleAndRecordsPolicy() {
        CharacterSpecification character = characterWithPolicies(Map.of(
                "identity", LoadingPolicy.DYNAMIC,
                "personality", LoadingPolicy.DYNAMIC));

        McsSelectionResult result = new McsSelector().select(character,
                new McsSelectionContext("unrelated", "history"));

        assertEquals(9, result.decisions().size());
        assertEquals(7, result.selectedModules().size());
        assertFalse(result.selectedModules().stream()
                .anyMatch(module -> module.name().equals("interests")));
        assertFalse(result.selectedModules().stream()
                .anyMatch(module -> module.name().equals("catchphrases")));
        assertEquals(LoadingPolicy.DYNAMIC, result.decisions().get(0).loadingPolicy());
        assertEquals("identity", result.decisions().get(0).module().name());
        assertEquals(LoadingPolicy.DYNAMIC, result.decisions().get(1).loadingPolicy());
        assertEquals("reference", result.decisions().get(1).selector());
    }

    private CharacterSpecification characterWithPolicies(Map<String, LoadingPolicy> overrides) {
        CharacterSpecification source = new CharacterLoader(MCS_ROOT).load();
        Map<String, LoadingPolicy> policies = new LinkedHashMap<>(source.loadingPolicies());
        policies.putAll(overrides);
        return new CharacterSpecification(source.name(), source.version(), source.description(),
                source.primaryLanguage(), source.fallbackLanguage(), source.role(), source.relationship(),
                source.defaultMode(), source.metadata(), policies, source.selectionMetadata(), source.identity(), source.personality(),
                source.values(), source.communication(), source.behavior(), source.reasoning(), source.interests(),
                source.boundaries(), source.catchphrases());
    }
}
