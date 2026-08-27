package com.minikun.pcs;

import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.Prompt;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.PromptRole;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PromptRenderer {
    private PromptRenderer() {
    }

    static Prompt render(PromptRequest request, List<McsModule> selectedModules) {
        return render(request, selectedModules, Optional.empty());
    }

    static Prompt render(
            PromptRequest request,
            List<McsModule> selectedModules,
            Optional<ContextProcessingResult> processingResult) {
        if (processingResult.isEmpty()) {
            return renderUnprocessed(request, selectedModules);
        }
        return renderProcessed(request, processingResult.get());
    }

    private static Prompt renderUnprocessed(PromptRequest request, List<McsModule> selectedModules) {
        List<String> sections = new ArrayList<>();
        sections.add(CorePromptFragments.persona(request.character(), selectedModules));
        sections.add(section("Runtime", request.runtime().content()));
        addOptional(sections, "Conversation",
                request.conversation() == null ? null : request.conversation().systemContent());
        addOptional(sections, "Personal User Context", request.personalUserModel().promptContent());
        String knowledge = request.knowledgeSelection().selectedCandidates().isEmpty()
            ? request.knowledge() == null ? null : request.knowledge().content()
            : request.knowledgeSelection().knowledgeContext().content();
        addOptional(sections, "Knowledge", knowledge);
        if (!request.capabilities().isEmpty()) {
            StringBuilder capabilities = new StringBuilder("[Capabilities]");
            for (CapabilityInstruction capability : request.capabilities()) {
                if (capability == null || capability.content() == null || capability.content().isBlank()) {
                    continue;
                }
                capabilities.append("\n").append(capability.name()).append(": ").append(capability.content());
            }
            if (capabilities.length() > "[Capabilities]".length()) {
                sections.add(capabilities.toString());
            }
        }
        return prompt(request, String.join("\n\n", sections));
    }

    private static Prompt renderProcessed(PromptRequest request, ContextProcessingResult processingResult) {
        Map<ContextBudgetSection, List<String>> contents = new EnumMap<>(ContextBudgetSection.class);
        for (ContextItem item : processingResult.selectedItems()) {
            contents.computeIfAbsent(item.section(), ignored -> new ArrayList<>()).add(item.content());
        }
        List<String> sections = new ArrayList<>();
        addProcessedCharacter(sections, contents);
        addProcessedRequired(sections, "Runtime", ContextBudgetSection.RUNTIME, contents);
        addProcessedOptional(sections, "Conversation", ContextBudgetSection.CONVERSATION, contents);
        addProcessedOptional(sections, "Personal User Context", ContextBudgetSection.USER_MODEL, contents);
        addProcessedOptional(sections, "Memory", ContextBudgetSection.MEMORY, contents);
        addProcessedOptional(sections, "Knowledge", ContextBudgetSection.KNOWLEDGE, contents);
        addProcessedOptional(sections, "Capabilities", ContextBudgetSection.CAPABILITIES, contents);
        return prompt(request, String.join("\n\n", sections));
    }

    private static Prompt prompt(PromptRequest request, String system) {
        List<PromptMessage> messages = new ArrayList<>();
        messages.add(new PromptMessage(PromptRole.SYSTEM, system));
        if (request.conversation() != null) {
            messages.addAll(request.conversation().messages());
        }
        messages.add(new PromptMessage(PromptRole.USER, request.userMessage().content()));
        return new Prompt(messages);
    }

    private static void addProcessedCharacter(
            List<String> sections,
            Map<ContextBudgetSection, List<String>> contents) {
        addProcessedRequired(sections, null, ContextBudgetSection.CHARACTER, contents);
    }

    private static void addProcessedRequired(
            List<String> sections,
            String label,
            ContextBudgetSection section,
            Map<ContextBudgetSection, List<String>> contents) {
        List<String> values = contents.get(section);
        if (values == null || values.isEmpty()) {
            throw new PromptException("processed context is missing required section: " + section);
        }
        sections.add(label == null ? values.get(0) : section(label, values.get(0)));
    }

    private static void addProcessedOptional(
            List<String> sections,
            String label,
            ContextBudgetSection section,
            Map<ContextBudgetSection, List<String>> contents) {
        List<String> values = contents.get(section);
        if (values != null && !values.isEmpty()) {
            addOptional(sections, label, String.join("\n", values));
        }
    }

    private static void addOptional(List<String> sections, String label, String content) {
        if (content != null && !content.isBlank()) {
            sections.add(section(label, content));
        }
    }

    private static String section(String label, String content) {
        return "[" + label + "]\n" + content;
    }
}
