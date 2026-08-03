package com.minikun.diagnostics;

import org.springframework.stereotype.Component;

import com.minikun.pcs.MinikunPersonaProvider;

@Component
public final class DiagnosticsPromptBuilder {
    private static final String INSTRUCTIONS = """
            Explain the supplied runtime diagnostics naturally and clearly.
            DiagnosticsSummary is authoritative. Preserve every supplied metric value exactly.
            Do not omit, alter, invent, infer, or reinterpret runtime facts or metrics.
            Natural-language generation may change presentation only, never factual content.
            If a metric is absent, say that runtime data is not yet available.
            Do not recommend fixes, optimizations, or configuration changes unless the user asks.
            Do not infer system health or predict failures from the supplied data.
            """.strip();

    private final MinikunPersonaProvider personaProvider;

    public DiagnosticsPromptBuilder(MinikunPersonaProvider personaProvider) {
        this.personaProvider = personaProvider;
    }

    public DiagnosticsPrompt build(DiagnosticsSummary summary, String userRequest) {
        return new DiagnosticsPrompt(personaProvider.fragment(), INSTRUCTIONS, summary, userRequest);
    }
}