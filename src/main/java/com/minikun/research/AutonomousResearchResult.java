package com.minikun.research;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.model.KnowledgeContext;
import java.util.List;
import java.util.Objects;

public record AutonomousResearchResult(
        KnowledgeContext searchKnowledge,
        List<KnowledgeCandidate> browserCandidates,
        ResearchTrace trace) {
    public AutonomousResearchResult {
        searchKnowledge = searchKnowledge == null ? KnowledgeContext.empty() : searchKnowledge;
        browserCandidates = browserCandidates == null ? List.of() : List.copyOf(browserCandidates);
        trace = Objects.requireNonNullElse(trace, ResearchTrace.EMPTY);
    }
}
