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
        SearchDecision searchDecision,
        DeviceLocationContext deviceLocationContext) {
    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext) {
        this(selection, consolidation, searchSignals, searchContext, ResearchTrace.EMPTY, null,
                DeviceLocationContext.EMPTY);
    }

    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext,
            ResearchTrace researchTrace) {
        this(selection, consolidation, searchSignals, searchContext, researchTrace, null,
                DeviceLocationContext.EMPTY);
    }

    ChatKnowledgeSelection(
            KnowledgeSelection selection,
            KnowledgeConsolidation consolidation,
            SearchSelectionSignals searchSignals,
            SearchContext searchContext,
            ResearchTrace researchTrace,
            SearchDecision searchDecision) {
        this(selection, consolidation, searchSignals, searchContext, researchTrace, searchDecision,
                DeviceLocationContext.EMPTY);
    }

    ChatKnowledgeSelection {
        researchTrace = researchTrace == null ? ResearchTrace.EMPTY : researchTrace;
        deviceLocationContext = deviceLocationContext == null
                ? DeviceLocationContext.EMPTY : deviceLocationContext;
    }
}
