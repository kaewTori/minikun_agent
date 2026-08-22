package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;

record ChatKnowledgeSelection(
        KnowledgeSelection selection,
        KnowledgeConsolidation consolidation,
        SearchSelectionSignals searchSignals,
        SearchContext searchContext) {
}
