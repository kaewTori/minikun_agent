package com.minikun.personality.runtime;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.personality.conversation.ConversationPolicy;
import com.minikun.personality.conversation.ConversationPolicyEngine;
import com.minikun.personality.model.Mood;
import com.minikun.personality.model.MoodSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * Derives a small, prompt-safe response-style overlay from the latest turn.
 * The classification is deliberately deterministic and does not retain the message.
 */
public final class ConversationStyleAdvisor {
    private final ConversationPolicyEngine policyEngine = new ConversationPolicyEngine();
    private static final String BASE_INSTRUCTION = "Respond to the user's actual intent directly. Use conversation "
            + "history to resolve references and implied follow-ups, and do not ask the user to repeat information "
            + "already available. The current request wins over older context. Match the user's language, register, "
            + "and requested depth. Start with the answer or a relevant acknowledgement, not a restatement of the "
            + "question or a description of your process. Prefer natural prose; use headings or lists only when they "
            + "materially improve clarity. Ask at most one clarifying question, and only when a reasonable assumption "
            + "would materially change the answer; otherwise state the assumption briefly and proceed. Use names, "
            + "self-reference, catchphrases, and offers of further help sparingly so they do not sound repetitive or "
            + "scripted. End when the useful answer is complete. "
            + "Interpret Thai negation, sarcasm, indirect requests, dialect and omitted subjects from context; "
            + "do not treat quoted speech or someone else's preference as the user's. If the meaning is unclear, "
            + "acknowledge uncertainty; ask before an action whose target or effect is ambiguous. "
            + "A request for today/this turn overrides style only for this turn. A present correction overrides "
            + "stored preferences and summaries immediately. Memory timestamps describe validity, not certainty: "
            + "use active facts for current questions and dated facts for historical questions. Do not revive "
            + "an expired preference, infer a new preference from a withdrawal, or invent a date or missing fact.";

    public ConversationStyle advise(String latestUserMessage) {
        return advise(latestUserMessage, List.of());
    }

    public ConversationStyle advise(String latestUserMessage, List<ChatMessage> recentHistory) {
        ConversationPolicy policy = policyEngine.evaluate(latestUserMessage, recentHistory);
        MoodSnapshot mood = policy.mood();
        return new ConversationStyle(mood,
                BASE_INSTRUCTION + moodInstruction(mood.mood()) + " " + policy.promptInstruction(), policy);
    }

    private String moodInstruction(Mood mood) {
        return switch (mood) {
            case SUPPORTIVE -> " The user may be having a difficult moment. Briefly acknowledge the specific feeling "
                    + "or situation before giving advice. Do not rush into a checklist, generic reassurance, or "
                    + "forced positivity. If action is useful, begin with one small, practical next step.";
            case CONCERNED -> " The message may indicate urgent distress. Respond calmly and plainly, take the "
                    + "signal seriously, and prioritize immediate safety and concrete real-world support without "
                    + "dramatizing or overwhelming the user.";
            case PLAYFUL -> " Light playfulness is welcome when it fits, but do not force jokes or trade accuracy "
                    + "for personality.";
            case FOCUSED -> " Keep social filler light, reason from the available evidence, and make the result and "
                    + "next action easy to identify.";
            case CALM -> " Keep the tone warm, relaxed, and unforced.";
        };
    }

    public record ConversationStyle(MoodSnapshot mood, String instruction, ConversationPolicy policy) {
        public ConversationStyle {
            mood = Objects.requireNonNull(mood, "mood");
            policy = Objects.requireNonNull(policy, "policy");
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("instruction must not be blank");
            }
            instruction = instruction.trim();
        }
    }
}
