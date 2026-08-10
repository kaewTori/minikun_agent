package com.minikun.pcs;

import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.UserMessage;

import java.util.ArrayList;
import java.util.List;

public final class ContextItemAssembler {
    private static final int REQUIRED_PRIORITY = 0;
    private static final int OPTIONAL_PRIORITY = 1;

    public List<ContextItem> assemble(PromptRequest request, List<McsModule> selectedModules) {
        if (request == null) {
            throw new PromptException("request must not be null");
        }
        if (selectedModules == null) {
            throw new PromptException("selected modules must not be null");
        }

        List<ContextItem> items = new ArrayList<>();
        items.add(new ContextItem(ContextBudgetSection.CHARACTER,
                CorePromptFragments.persona(request.character(), selectedModules),
                REQUIRED_PRIORITY, true));
        items.add(new ContextItem(ContextBudgetSection.RUNTIME, request.runtime().content(),
                REQUIRED_PRIORITY, true));
        addOptional(items, ContextBudgetSection.CONVERSATION,
                request.conversation() == null ? null : request.conversation().content());
        addOptional(items, ContextBudgetSection.KNOWLEDGE, knowledgeContent(request));
        addCapabilities(items, request.capabilities());
        UserMessage userMessage = request.userMessage();
        items.add(new ContextItem(ContextBudgetSection.USER_MESSAGE, userMessage.content(),
                REQUIRED_PRIORITY, true));
        return List.copyOf(items);
    }

    private String knowledgeContent(PromptRequest request) {
        return request.knowledgeSelection().selectedCandidates().isEmpty()
                ? request.knowledge() == null ? null : request.knowledge().content()
                : request.knowledgeSelection().knowledgeContext().content();
    }

    private void addCapabilities(List<ContextItem> items, List<CapabilityInstruction> capabilities) {
        if (capabilities.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        for (CapabilityInstruction capability : capabilities) {
            if (capability == null || capability.content() == null || capability.content().isBlank()) {
                continue;
            }
            if (content.length() > 0) {
                content.append("\n");
            }
            content.append(capability.name()).append(": ").append(capability.content());
        }
        addOptional(items, ContextBudgetSection.CAPABILITIES, content.toString());
    }

    private void addOptional(List<ContextItem> items, ContextBudgetSection section, String content) {
        if (content != null && !content.isBlank()) {
            items.add(new ContextItem(section, content, OPTIONAL_PRIORITY, false));
        }
    }
}