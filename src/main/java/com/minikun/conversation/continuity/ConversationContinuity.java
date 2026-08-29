package com.minikun.conversation.continuity;

import java.util.List;
import java.util.Objects;

/** Turn-local resolution of an abbreviated message against recent conversation. */
public record ConversationContinuity(
        boolean followUp,
        String previousTopic,
        String searchAnchor,
        List<String> entities,
        String resolvedQuery,
        boolean visualFollowUp,
        double confidence) {
    public static final ConversationContinuity NONE = new ConversationContinuity(
            false, "", "", List.of(), "", false, 0.0);

    public ConversationContinuity {
        previousTopic = Objects.requireNonNullElse(previousTopic, "").strip();
        searchAnchor = Objects.requireNonNullElse(searchAnchor, "").strip();
        entities = entities == null ? List.of() : List.copyOf(entities);
        resolvedQuery = Objects.requireNonNullElse(resolvedQuery, "").strip();
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public String promptInstruction() {
        if (!followUp || previousTopic.isBlank()) {
            return "";
        }
        StringBuilder instruction = new StringBuilder(
                "Treat the latest user message as a continuation of this recent topic: \"")
                .append(previousTopic)
                .append("\". ");
        if (!entities.isEmpty()) {
            instruction.append("The referenced subject is: ")
                    .append(String.join(", ", entities))
                    .append(". ");
        }
        instruction.append("Resolve pronouns and short phrases against that topic. "
                + "Do not switch to a different person, product, or subject, and do not ask the user "
                + "to repeat a subject already present in the recent conversation.");
        return instruction.toString();
    }
}
