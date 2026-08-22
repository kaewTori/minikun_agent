package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.research.ResearchTrace;

record ChatKnowledgeSelection(
        KnowledgeSelection selection,
        KnowledgeConsolidation consolidation,
        SearchSelectionSignals searchSignals,
        SearchContext searchContext,
        ResearchTrace researchTrace) {
    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext) {
        this(selection, consolidation, searchSignals, searchContext, ResearchTrace.EMPTY);
    }

    ChatKnowledgeSelection {
        researchTrace = researchTrace == null ? ResearchTrace.EMPTY : researchTrace;
    }
}
