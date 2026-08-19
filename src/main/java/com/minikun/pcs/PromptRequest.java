package com.minikun.pcs;

import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;
import com.minikun.personality.signal.PersonaSelectionSignals;
import com.minikun.personality.model.PersonalUserModel;

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
        ContextBudget contextBudget,
        PersonaSelectionSignals personaSelectionSignals,
        PersonalUserModel personalUserModel) {
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
            KnowledgeConsolidation knowledgeConsolidation,
            ContextBudget contextBudget,
            PersonaSelectionSignals personaSelectionSignals) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
                searchSelectionSignals, searchContext, knowledgeSelection, knowledgeConsolidation,
                contextBudget, personaSelectionSignals, PersonalUserModel.EMPTY);
    }

    public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
            SearchSelectionSignals.EMPTY, SearchContext.EMPTY, KnowledgeSelection.EMPTY,
            KnowledgeConsolidation.EMPTY, null, PersonaSelectionSignals.EMPTY);
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
            KnowledgeConsolidation.EMPTY, null, PersonaSelectionSignals.EMPTY);
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
            KnowledgeConsolidation.EMPTY, null, PersonaSelectionSignals.EMPTY);
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
            searchSelectionSignals, searchContext, knowledgeSelection, KnowledgeConsolidation.EMPTY,
            null, PersonaSelectionSignals.EMPTY);
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
            searchSelectionSignals, searchContext, knowledgeSelection, knowledgeConsolidation, null,
            PersonaSelectionSignals.EMPTY);
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
            KnowledgeConsolidation knowledgeConsolidation,
            ContextBudget contextBudget) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
                searchSelectionSignals, searchContext, knowledgeSelection, knowledgeConsolidation,
                contextBudget, PersonaSelectionSignals.EMPTY);
    }

    public PromptRequest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        searchSelectionSignals = searchSelectionSignals == null
                ? SearchSelectionSignals.EMPTY : searchSelectionSignals;
        searchContext = searchContext == null ? SearchContext.EMPTY : searchContext;
        knowledgeSelection = knowledgeSelection == null ? KnowledgeSelection.EMPTY : knowledgeSelection;
        knowledgeConsolidation = knowledgeConsolidation == null
            ? KnowledgeConsolidation.EMPTY : knowledgeConsolidation;
        personaSelectionSignals = personaSelectionSignals == null
            ? PersonaSelectionSignals.EMPTY : personaSelectionSignals;
        personalUserModel = personalUserModel == null ? PersonalUserModel.EMPTY : personalUserModel;
    }
}
