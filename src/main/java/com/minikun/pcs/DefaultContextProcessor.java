package com.minikun.pcs;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class DefaultContextProcessor implements ContextProcessor {
    private final ContextItemSelector selector;
    private final ContextCompressor compressor;

    public DefaultContextProcessor(ContextItemSelector selector, ContextCompressor compressor) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor must not be null");
    }

    @Override
    public ContextProcessingResult process(ContextProcessingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ContextItemSelectionResult selection = selector.select(request.budget(), request.items());
        List<ContextCompressionResult> compressionResults = selection.selectedItems().stream()
                .map(compressor::compress)
                .toList();

        EnumMap<ContextBudgetSection, Long> postCompressionUsage = new EnumMap<>(ContextBudgetSection.class);
        long originalSelectedCharacters = 0;
        long compressedSelectedCharacters = 0;
        int compressedCount = 0;
        for (ContextCompressionResult compressionResult : compressionResults) {
            ContextItem compressedItem = compressionResult.compressedItem();
            postCompressionUsage.merge(compressedItem.section(), (long) compressedItem.size(), Math::addExact);
            originalSelectedCharacters = Math.addExact(originalSelectedCharacters, compressionResult.originalSize());
            compressedSelectedCharacters = Math.addExact(compressedSelectedCharacters, compressionResult.compressedSize());
            if (compressionResult.compressionApplied()) {
                compressedCount++;
            }
        }

        ContextProcessingResult.Diagnostics diagnostics = new ContextProcessingResult.Diagnostics(
                request.items().size(),
                selection.selectedItems().size(),
                selection.evictions().size(),
                compressedCount,
                originalSelectedCharacters,
                compressedSelectedCharacters,
                Math.subtractExact(originalSelectedCharacters, compressedSelectedCharacters),
                selection.requiredOverflow());
        return new ContextProcessingResult(selection, compressionResults, postCompressionUsage, diagnostics);
    }
}
