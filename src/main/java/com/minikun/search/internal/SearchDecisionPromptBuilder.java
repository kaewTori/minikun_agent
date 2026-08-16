package com.minikun.search.internal;

import com.minikun.search.model.SearchDecisionPrompt;
import java.time.LocalDate;

final class SearchDecisionPromptBuilder {
    private static final String INSTRUCTIONS = """
            Classify whether the user request needs external web search.
            Do not explain your reasoning. Do not output thinking, markdown, or any text outside the JSON object.
            Output exactly one object in this format:
            {"shouldSearch":true,"reason":"CURRENT_INFORMATION"}
            Use exactly these reason values: CURRENT_INFORMATION, FACT_LOOKUP, EXTERNAL_RESOURCE, GENERAL_KNOWLEDGE.
            Use shouldSearch=false for GENERAL_KNOWLEDGE when external search is unnecessary.
            Never output RULE_FALLBACK.
            """.strip();

    SearchDecisionPrompt build(LocalDate currentDate, String userMessage) {
        return new SearchDecisionPrompt(INSTRUCTIONS, currentDate.toString(), userMessage);
    }

    SearchDecisionPrompt build(LocalDate currentDate, String userMessage, String conversationContext) {
        String context = conversationContext == null || conversationContext.isBlank()
                ? "No prior conversation context is available."
                : "Prior conversation context (use only to resolve references; do not search it):\n"
                        + conversationContext;
        return new SearchDecisionPrompt(INSTRUCTIONS, currentDate.toString(),
                context + "\n\nCurrent user message:\n" + userMessage);
    }
}
