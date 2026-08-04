package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteralTermMatcherTest {
    private final LiteralTermMatcher matcher = new LiteralTermMatcher();

    @Test
    void normalizesCaseTrimAndWhitespaceForLiteralMatching() {
        McsSelectionMetadata metadata = new McsSelectionMetadata(List.of("Artificial Intelligence"));

        assertTrue(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("  artificial   intelligence  ", "")));
    }

    @Test
    void evaluatesCurrentMessageAndConversationHistoryDeterministically() {
        McsSelectionMetadata metadata = new McsSelectionMetadata(List.of("linux"));

        assertTrue(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("unrelated", "Earlier discussion about Linux")));
        assertTrue(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("Linux first", "unrelated")));
        assertFalse(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("unrelated", "also unrelated")));
    }

    @Test
    void usesLiteralSubstringMatchingOnly() {
        McsSelectionMetadata metadata = new McsSelectionMetadata(List.of("plan"));

        assertTrue(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("planning", "")));
        assertFalse(matcher.matches(Optional.of(metadata),
                new McsSelectionContext("pl an", "")));
    }

    @Test
    void missingMetadataSkipsAndRepeatedEvaluationIsDeterministic() {
        McsSelectionContext context = new McsSelectionContext("plan", "history");

        assertFalse(matcher.matches(Optional.empty(), context));
        assertTrue(matcher.matches(Optional.of(new McsSelectionMetadata(List.of("plan"))), context));
        assertTrue(matcher.matches(Optional.of(new McsSelectionMetadata(List.of("plan"))), context));
    }

        @Test
        void ignoresAdditionalContextAttributes() {
                McsSelectionContext context = new McsSelectionContext(
                                "unrelated", "also unrelated",
                                new ConversationAttributes("conversation-1", 10, false),
                                new RuntimeAttributes(true, true, ResponseMode.DEFAULT));

                assertFalse(matcher.matches(Optional.of(new McsSelectionMetadata(List.of("plan"))), context));
        }
}