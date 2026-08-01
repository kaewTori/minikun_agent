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
        UserMessage userMessage) {
    public PromptRequest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}