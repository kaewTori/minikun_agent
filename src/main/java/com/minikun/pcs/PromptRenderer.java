package com.minikun.pcs;

import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.Prompt;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.PromptRole;

import java.util.ArrayList;
import java.util.List;

final class PromptRenderer {
    private PromptRenderer() {
    }

    static Prompt render(PromptRequest request) {
        List<String> sections = new ArrayList<>();
        sections.add(CorePromptFragments.persona(request.character()));
        sections.add(section("Runtime", request.runtime().content()));
        addOptional(sections, "Conversation", request.conversation() == null ? null : request.conversation().content());
        addOptional(sections, "Knowledge", request.knowledge() == null ? null : request.knowledge().content());
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
        return new Prompt(List.of(
            new PromptMessage(PromptRole.SYSTEM, String.join("\n\n", sections)),
            new PromptMessage(PromptRole.USER, request.userMessage().content())));
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