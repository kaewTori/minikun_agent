package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;

import java.util.List;

public record PromptRequest(
        CharacterSpecification character,
        RuntimeContext runtime,
        ConversationContext conversation,
        KnowledgeContext knowledge,
        List<CapabilityInstruction> capabilities,
        UserMessage userMessage,
        SearchSelectionSignals searchSelectionSignals,
        SearchContext searchContext,
        KnowledgeSelection knowledgeSelection,
        KnowledgeConsolidation knowledgeConsolidation,
        ContextBudget contextBudget) {
    public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            SearchSelectionSignals.EMPTY, SearchContext.EMPTY, KnowledgeSelection.EMPTY,
            KnowledgeConsolidation.EMPTY, null);
        }

        public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage,
            SearchSelectionSignals searchSelectionSignals) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            searchSelectionSignals, SearchContext.EMPTY, KnowledgeSelection.EMPTY,
            KnowledgeConsolidation.EMPTY, null);
    }

    public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage,
            SearchSelectionSignals searchSelectionSignals,
            SearchContext searchContext) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            searchSelectionSignals, searchContext, KnowledgeSelection.EMPTY,
            KnowledgeConsolidation.EMPTY, null);
    }

        public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage,
            SearchSelectionSignals searchSelectionSignals,
            SearchContext searchContext,
            KnowledgeSelection knowledgeSelection) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            searchSelectionSignals, searchContext, knowledgeSelection, KnowledgeConsolidation.EMPTY);
    }

    public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage,
            SearchSelectionSignals searchSelectionSignals,
            SearchContext searchContext,
            KnowledgeSelection knowledgeSelection,
            KnowledgeConsolidation knowledgeConsolidation) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            searchSelectionSignals, searchContext, knowledgeSelection, knowledgeConsolidation, null);
        }

    public PromptRequest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        searchSelectionSignals = searchSelectionSignals == null
                ? SearchSelectionSignals.EMPTY : searchSelectionSignals;
        searchContext = searchContext == null ? SearchContext.EMPTY : searchContext;
        knowledgeSelection = knowledgeSelection == null ? KnowledgeSelection.EMPTY : knowledgeSelection;
        knowledgeConsolidation = knowledgeConsolidation == null
            ? KnowledgeConsolidation.EMPTY : knowledgeConsolidation;
    }
}