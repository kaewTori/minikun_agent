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
        SearchSelectionSignals searchSelectionSignals) {
    public PromptRequest(
            CharacterSpecification character,
            RuntimeContext runtime,
            ConversationContext conversation,
            KnowledgeContext knowledge,
            List<CapabilityInstruction> capabilities,
            UserMessage userMessage) {
        this(character, runtime, conversation, knowledge, capabilities, userMessage,
                SearchSelectionSignals.EMPTY);
    }

    public PromptRequest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        searchSelectionSignals = searchSelectionSignals == null
                ? SearchSelectionSignals.EMPTY : searchSelectionSignals;
    }
}