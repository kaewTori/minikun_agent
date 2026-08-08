package com.minikun.pcs;

import java.util.Objects;

public final class DefaultContextCompressor implements ContextCompressor {
    @Override
    public ContextCompressionResult compress(ContextItem item) {
        Objects.requireNonNull(item, "item must not be null");

        String compressedContent = normalizeLineEndings(item.content());
        compressedContent = removeTrailingWhitespace(compressedContent);
        compressedContent = compactBlankLines(compressedContent);

        ContextItem compressedItem = new ContextItem(
                item.section(),
                compressedContent,
                item.priority(),
                item.required());
        return new ContextCompressionResult(item, compressedItem);
    }

    private static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String removeTrailingWhitespace(String content) {
        return content.replaceAll("[ \\t]+(?=\\n|$)", "");
    }

    private static String compactBlankLines(String content) {
        return content.replaceAll("\\n{3,}", "\n\n");
    }
}