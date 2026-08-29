package com.minikun.conversation.repair;

import com.minikun.conversation.continuity.ConversationContinuity;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.model.ImageSource;
import java.util.Locale;

/** Detects likely cross-turn retrieval drift before the model writes its answer. */
public final class ConversationRepairAdvisor {
    public ConversationRepairAdvice advise(
            ConversationContinuity continuity,
            KnowledgeSelection knowledge,
            SearchContext searchContext) {
        if (continuity == null || !continuity.followUp()) {
            return ConversationRepairAdvice.NONE;
        }
        KnowledgeSelection safeKnowledge = knowledge == null ? KnowledgeSelection.EMPTY : knowledge;
        SearchContext safeSearch = searchContext == null ? SearchContext.EMPTY : searchContext;

        if (!continuity.entities().isEmpty()
                && safeSearch.searchAttempted()
                && hasEvidence(safeKnowledge)
                && !evidenceMentionsAnyEntity(safeKnowledge, continuity)) {
            return advice(ConversationRepairAdvice.Reason.SUBJECT_MISMATCH,
                    "The retrieved evidence does not clearly match the resolved subject "
                            + String.join(", ", continuity.entities()) + ". Ignore unrelated results. "
                            + "Do not silently replace the person, product, or topic. State the mismatch briefly "
                            + "and keep the answer anchored to the user's original subject.");
        }
        if (continuity.visualFollowUp() && safeSearch.searchAttempted() && safeKnowledge.images().isEmpty()) {
            return advice(ConversationRepairAdvice.Reason.VISUAL_RESULT_MISSING,
                    "The user asked for visuals about the resolved subject, but no matching image was returned. "
                            + "Say that this retrieval attempt found no usable image; do not claim that the system "
                            + "cannot display images, and do not substitute an unrelated subject.");
        }
        return advice(ConversationRepairAdvice.Reason.FOLLOW_UP_GUARD,
                "Before answering, verify that names, pronouns, recommendations, and visuals still refer to the "
                        + "resolved prior topic. If the evidence conflicts with it, correct course explicitly "
                        + "instead of continuing with a plausible but different subject.");
    }

    private ConversationRepairAdvice advice(ConversationRepairAdvice.Reason reason, String instruction) {
        return new ConversationRepairAdvice(true, reason, instruction);
    }

    private boolean hasEvidence(KnowledgeSelection knowledge) {
        return !knowledge.selectedCandidates().isEmpty() || !knowledge.images().isEmpty();
    }

    private boolean evidenceMentionsAnyEntity(
            KnowledgeSelection knowledge, ConversationContinuity continuity) {
        StringBuilder evidence = new StringBuilder();
        knowledge.selectedCandidates().forEach(candidate -> evidence
                .append(candidate.content()).append(' ')
                .append(candidate.provenance()).append(' '));
        for (ImageSource image : knowledge.images()) {
            evidence.append(image.title()).append(' ')
                    .append(image.description()).append(' ')
                    .append(image.sourceUrl()).append(' ')
                    .append(image.url()).append(' ');
        }
        String lower = evidence.toString().toLowerCase(Locale.ROOT);
        return continuity.entities().stream()
                .map(entity -> entity.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
    }
}
