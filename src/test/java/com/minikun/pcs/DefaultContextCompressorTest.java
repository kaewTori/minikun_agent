package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextCompressorTest {
    private final ContextCompressor compressor = new DefaultContextCompressor();

    @Test
    void noOpContentReportsNoCompression() {
        ContextCompressionResult result = compressor.compress(item("hello world", false));

        assertEquals("hello world", result.compressedItem().content());
        assertEquals(11L, result.originalSize());
        assertEquals(11L, result.compressedSize());
        assertEquals(0L, result.charactersSaved());
        assertFalse(result.compressionApplied());
    }

    @Test
    void emptyContentRemainsEmpty() {
        ContextCompressionResult result = compressor.compress(item("", false));

        assertEquals("", result.compressedItem().content());
        assertEquals(0L, result.originalSize());
        assertEquals(0L, result.compressedSize());
        assertEquals(0L, result.charactersSaved());
        assertFalse(result.compressionApplied());
    }

    @Test
    void normalizesLineEndingsAndRemovesTrailingSpacesAndTabs() {
        ContextCompressionResult result = compressor.compress(item("one  \r\ntwo\t\rthree  ", false));

        assertEquals("one\ntwo\nthree", result.compressedItem().content());
        assertEquals(6L, result.charactersSaved());
    }

    @Test
    void compactsRunsOfBlankLinesWithoutRemovingParagraphSeparators() {
        ContextCompressionResult result = compressor.compress(item("one\n\n\n\n two\n\nthree", false));

        assertEquals("one\n\n two\n\nthree", result.compressedItem().content());
    }

    @Test
    void preservesIndentationOrderingPunctuationAndWords() {
        String content = "  if (value) {\n    return value;\n  }\nkey:  value\nquoted: \"a  b\"";

        ContextCompressionResult result = compressor.compress(item(content, true));

        assertEquals(content, result.compressedItem().content());
        assertTrue(result.compressedItem().required());
        assertEquals(ContextBudgetSection.RUNTIME, result.compressedItem().section());
        assertEquals(7, result.compressedItem().priority());
    }

    @Test
    void isDeterministicAndIdempotent() {
        ContextItem source = item("one\r\n\r\n\r\n two  ", false);

        ContextCompressionResult first = compressor.compress(source);
        ContextCompressionResult repeated = compressor.compress(source);
        ContextCompressionResult second = compressor.compress(first.compressedItem());

        assertEquals(first, repeated);
        assertEquals(first.compressedItem(), second.compressedItem());
        assertEquals(first.compressedSize(), second.compressedSize());
        assertEquals(0L, second.charactersSaved());
        assertFalse(second.compressionApplied());
    }

    @Test
    void preservesRequiredItemAndDoesNotTruncateContent() {
        String content = "required\n\n\ncontent";

        ContextCompressionResult result = compressor.compress(item(content, true));

        assertTrue(result.compressedItem().required());
        assertEquals("required\n\ncontent", result.compressedItem().content());
        assertTrue(result.compressedItem().content().contains("required"));
        assertTrue(result.compressedItem().content().contains("content"));
    }

    @Test
    void rejectsNullItems() {
        assertThrows(NullPointerException.class, () -> compressor.compress(null));
    }

    @Test
    void resultIsImmutableAndRejectsInvalidMetadataOrGrowth() {
        ContextItem original = item("content", false);
        ContextItem compressed = item("content", false);
        ContextItem required = item("content", true);

        ContextCompressionResult result = new ContextCompressionResult(original, compressed);

        assertNotSame(original, result.compressedItem());
        assertEquals(original, result.originalItem());
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompressionResult(original, required));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompressionResult(item("a", false), item("ab", false)));
    }

    private static ContextItem item(String content, boolean required) {
        return new ContextItem(ContextBudgetSection.RUNTIME, content, 7, required);
    }
}