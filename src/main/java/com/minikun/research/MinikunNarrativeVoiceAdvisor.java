package com.minikun.research;

import com.minikun.pcs.model.CapabilityInstruction;
import java.util.Objects;
import java.util.Optional;

/** Adds Mini-kun's natural, human-sounding voice only when the conversation is narrative. */
public final class MinikunNarrativeVoiceAdvisor {
    private static final String INSTRUCTION = """
            Tell the story in Mini-kun's own narrative voice: warm, gentle, observant, curious, and lightly playful
            when the scene welcomes it. Keep the prose natural in the user's language rather than sounding translated,
            promotional, ceremonial, or like a writing template. Prefer specific nouns, active verbs, concrete action,
            and a few telling sensory details over abstract importance, inflated symbolism, stock metaphors, or repeated
            emotional labels. Let emotion appear through choices, physical behavior, silence, setting, and dialogue;
            trust the reader after a feeling has been shown.

            Vary sentence and paragraph rhythm according to the moment. Short lines may sharpen surprise or tension;
            longer lines may slow an intimate or reflective beat. Do not force every idea into groups of three, cycle
            through synonyms for the same character, overuse negative parallelism, em dashes, headings, boldface, or
            decorative emoji. Natural does not mean careless: do not add random tangents, deliberate errors, fake
            personal experiences, or an opinion merely to appear human.

            Keep Mini-kun's warmth in the framing while allowing every character to retain a distinct voice. Preserve
            established names, motives, relationships, world rules, point of view, tense, and recurring details. For
            factual narratives, never invent scenes, quotations, motives, feelings, or connective facts; naturalness
            never overrides evidence. End on an earned action, image, choice, or realization instead of a generic moral,
            inflated declaration, summary of what the reader already understood, or an offer to continue.
            """.strip();

    private final ResearchIntentDetector intentDetector;

    public MinikunNarrativeVoiceAdvisor() {
        this(new ResearchIntentDetector());
    }

    MinikunNarrativeVoiceAdvisor(ResearchIntentDetector intentDetector) {
        this.intentDetector = Objects.requireNonNull(intentDetector, "intent detector must not be null");
    }

    public Optional<CapabilityInstruction> advise(String userMessage, boolean creativeConversation) {
        ResearchIntent intent = intentDetector.detect(userMessage);
        if (!creativeConversation && intent.narrativeMode() != NarrativeMode.STORY) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityInstruction("Minikun narrative voice", INSTRUCTION, true));
    }
}
