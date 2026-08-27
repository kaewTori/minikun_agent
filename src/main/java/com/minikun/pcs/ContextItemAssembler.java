package com.minikun.pcs;

import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.UserMessage;
import com.minikun.context.selection.ContextSourcePriorityPolicy;
import com.minikun.context.selection.DefaultContextSourcePriorityPolicy;

import java.util.ArrayList;
import java.util.List;

public final class ContextItemAssembler {
    private static final int REQUIRED_PRIORITY = 0;
    private static final int OPTIONAL_PRIORITY = 1;
    private final ContextSourcePriorityPolicy sourcePriorityPolicy;

    public ContextItemAssembler() {
        this(new DefaultContextSourcePriorityPolicy());
    }

    public ContextItemAssembler(ContextSourcePriorityPolicy sourcePriorityPolicy) {
        this.sourcePriorityPolicy = java.util.Objects.requireNonNull(
                sourcePriorityPolicy, "source priority policy must not be null");
    }

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
        addConversation(items, request);
        addOptional(items, ContextBudgetSection.USER_MODEL,
                request.personalUserModel().promptContent(), 1);
        addKnowledge(items, request);
        addCapabilities(items, request.capabilities());
        UserMessage userMessage = request.userMessage();
        items.add(new ContextItem(ContextBudgetSection.USER_MESSAGE, userMessage.content(),
                REQUIRED_PRIORITY, true));
        return List.copyOf(items);
    }

    private void addConversation(List<ContextItem> items, PromptRequest request) {
        String content = request.conversation() == null ? null : request.conversation().systemContent();
        if (content == null || content.isBlank()) {
            return;
        }
        if (request.contextBudget() != null) {
            content = retainRecentConversation(
                    content, request.contextBudget().allocation(ContextBudgetSection.CONVERSATION));
        }
        addOptional(items, ContextBudgetSection.CONVERSATION, content, 3);
    }

    private String retainRecentConversation(String content, long maximumCharacters) {
        if (maximumCharacters <= 0) {
            return "";
        }
        int limit = (int) Math.min(maximumCharacters, Integer.MAX_VALUE);
        if (content.length() <= limit) {
            return content;
        }
        String marker = "[Earlier conversation omitted]\n\n";
        if (limit <= marker.length()) {
            return content.substring(content.length() - limit);
        }
        int target = content.length() - (limit - marker.length());
        int boundary = nextTurnBoundary(content, target);
        String recent = boundary < 0 ? content.substring(target) : content.substring(boundary);
        return marker + recent;
    }

    private int nextTurnBoundary(String content, int start) {
        int user = content.indexOf("\n\nuser: ", start);
        int assistant = content.indexOf("\n\nassistant: ", start);
        int boundary;
        if (user < 0) {
            boundary = assistant;
        } else if (assistant < 0) {
            boundary = user;
        } else {
            boundary = Math.min(user, assistant);
        }
        return boundary < 0 ? -1 : boundary + 2;
    }

    private void addKnowledge(List<ContextItem> items, PromptRequest request) {
        if (request.knowledgeSelection().selectedCandidates().isEmpty()) {
            addOptional(items, ContextBudgetSection.KNOWLEDGE,
                    request.knowledge() == null ? null : request.knowledge().content(), 1);
            return;
        }
        request.knowledgeSelection().selectedCandidates().forEach(candidate -> {
            ContextBudgetSection section = candidate.source() == KnowledgeSource.MEMORY
                    ? ContextBudgetSection.MEMORY
                    : ContextBudgetSection.KNOWLEDGE;
            int priority = sourcePriorityPolicy.priority(candidate.source());
            addOptional(items, section, candidate.content(), priority);
        });
    }

    private void addCapabilities(List<ContextItem> items, List<CapabilityInstruction> capabilities) {
        if (capabilities.isEmpty()) {
            return;
        }
        for (CapabilityInstruction capability : capabilities) {
            if (capability == null || capability.content() == null || capability.content().isBlank()) {
                continue;
            }
            String content = capability.name() + ": " + capability.content();
            addOptional(items, ContextBudgetSection.CAPABILITIES, content, 0, capability.required());
        }
    }

    private void addOptional(
            List<ContextItem> items,
            ContextBudgetSection section,
            String content,
            int priority) {
        addOptional(items, section, content, priority, false);
    }

    private void addOptional(
            List<ContextItem> items,
            ContextBudgetSection section,
            String content,
            int priority,
            boolean required) {
        if (content != null && !content.isBlank()) {
            items.add(new ContextItem(section, content, Math.max(priority, OPTIONAL_PRIORITY), required));
        }
    }
}
