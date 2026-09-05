package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.research.ResearchTrace;
import com.minikun.search.model.SearchDecision;

record ChatKnowledgeSelection(
        KnowledgeSelection selection,
        KnowledgeConsolidation consolidation,
        SearchSelectionSignals searchSignals,
        SearchContext searchContext,
        ResearchTrace researchTrace,
        SearchDecision searchDecision) {
    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext) {
        this(selection, consolidation, searchSignals, searchContext, ResearchTrace.EMPTY, null);
    }

    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext,
            ResearchTrace researchTrace) {
        this(selection, consolidation, searchSignals, searchContext, researchTrace, null);
    }

    ChatKnowledgeSelection {
        researchTrace = researchTrace == null ? ResearchTrace.EMPTY : researchTrace;
    }
}
