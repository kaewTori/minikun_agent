package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.model.CooperationRoutingDecision;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.search.model.SearchDecision;
import java.util.Locale;
import java.util.Objects;

/** One immutable routing decision shared by every stage of a chat turn. */
record TurnPlan(
        Intent intent,
        Execution execution,
        CooperationRoutingDecision cooperation,
        boolean needsMemory,
        boolean needsPersonalKnowledge,
        boolean needsWeb,
        boolean needsTools,
        boolean needsVision,
        boolean deepResearch,
        boolean creative,
        boolean ambiguous,
        double confidence,
        String reason,
        boolean imageOutput,
        String routeSource) {

    TurnPlan {
        intent = Objects.requireNonNullElse(intent, Intent.GENERAL);
        execution = Objects.requireNonNullElse(execution, Execution.DIRECT_STREAM);
        cooperation = Objects.requireNonNullElse(cooperation, CooperationRoutingDecision.low());
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("turn-plan confidence must be between 0 and 1");
        }
        reason = Objects.requireNonNullElse(reason, "deterministic_default").trim();
        routeSource = Objects.requireNonNullElse(routeSource, "turn_planner").trim();
    }

    TurnPlan refine(ChatKnowledgeSelection knowledge) {
        if (knowledge == null) return this;
        SearchDecision search = knowledge.searchDecision();
        boolean externalKnowledge = knowledge.selection().selectedCandidates().stream()
                .anyMatch(candidate -> candidate.source() == KnowledgeSource.SEARCH
                        || candidate.source() == KnowledgeSource.BROWSER);
        boolean searched = search != null && search.shouldSearch()
                || knowledge.searchContext().searchAttempted() || externalKnowledge;
        boolean research = deepResearch || knowledge.researchTrace().autonomous()
                || search != null && "research".equals(search.planHints().intent());
        Intent refinedIntent = intent;
        if (research) refinedIntent = Intent.RESEARCH;
        else if (searched && intent != Intent.ACTION && intent != Intent.CREATIVE) refinedIntent = Intent.SEARCH;
        double refinedConfidence = search != null && search.planHints().confidence() > 0.0
                ? Math.max(confidence, search.planHints().confidence()) : confidence;
        return new TurnPlan(refinedIntent,
                research ? Execution.BACKGROUND : execution,
                cooperation, needsMemory, needsPersonalKnowledge, searched, needsTools, needsVision,
                research, creative, ambiguous, refinedConfidence,
                searched ? reason + "+search" : reason, imageOutput, routeSource);
    }

    String intentTag() { return intent.name().toLowerCase(Locale.ROOT); }
    String executionTag() { return execution.name().toLowerCase(Locale.ROOT); }

    enum Intent { COMPANION, GENERAL, WORK, ACTION, SEARCH, RESEARCH, TECHNICAL, CREATIVE, VISION }
    enum Execution { DIRECT_STREAM, TOOL_LOOP, BACKGROUND }
}
