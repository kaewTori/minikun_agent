package com.minikun.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.CorePromptFragments;
import com.minikun.pcs.MinikunPersonaProvider;

class DiagnosticsPromptBuilderTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void composesSharedPersonaBeforeDiagnosticsInstructionsAndSummary() {
        MinikunPersonaProvider provider = new MinikunPersonaProvider(character());
        DiagnosticsSummary summary = summary();

        DiagnosticsPrompt prompt = new DiagnosticsPromptBuilder(provider).build(summary, "/diagnostics");

        assertEquals(provider.fragment(), prompt.persona());
        assertSame(summary, prompt.summary());
        assertEquals("/diagnostics", prompt.userRequest());
        assertTrue(prompt.instructions().contains("DiagnosticsSummary is authoritative"));
        assertTrue(!prompt.instructions().contains(prompt.persona().content()));
    }

    @Test
    void sharedPersonaFragmentMatchesTheCoreCompositionSource() {
        CharacterLoader loader = new CharacterLoader(MCS_ROOT);
        MinikunPersonaProvider provider = new MinikunPersonaProvider(loader.load());

        assertEquals(CorePromptFragments.persona(loader.load()), provider.fragment().content());
    }

    @Test
    void instructionsAreDeterministicAndAbsentUserRequestIsEmpty() {
        DiagnosticsSummary summary = summary();
        MinikunPersonaProvider provider = new MinikunPersonaProvider(character());
        DiagnosticsPromptBuilder builder = new DiagnosticsPromptBuilder(provider);

        DiagnosticsPrompt first = builder.build(summary, null);
        DiagnosticsPrompt second = builder.build(summary, null);

        assertEquals(first, second);
        assertEquals("", first.userRequest());
    }

    private CharacterSpecification character() {
        return new CharacterLoader(MCS_ROOT).load();
    }

    private DiagnosticsSummary summary() {
        return new DiagnosticsSummary(
                MetricCount.present(3),
                new DurationSummary(MetricPresence.PRESENT, 3, 150, 50),
                new DiagnosticsSummary.CacheSummary(
                        MetricCount.present(1), MetricCount.present(2), MetricCount.present(1)),
                MetricCount.present(0),
                MetricCount.present(1),
                new DiagnosticsSummary.QualitySummary(
                        MetricCount.present(1), MetricCount.present(1), MetricCount.present(1)));
    }
}