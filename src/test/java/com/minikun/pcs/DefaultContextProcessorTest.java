package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextProcessorTest {
    private static final ContextBudgetSection SECTION = ContextBudgetSection.MEMORY;

    @Test
    void selectsThenCompressesSelectedItemsInInputOrder() {
        ContextItem first = item("first  ", 1, false);
        ContextItem second = item("second\r\n", 2, false);
        RecordingCompressor compressor = new RecordingCompressor();
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), compressor);

        ContextProcessingResult result = processor.process(request(20, first, second));

        assertEquals(List.of(first, second), compressor.seenItems);
        assertEquals(List.of("first", "second\n"), result.selectedItems().stream()
                .map(ContextItem::content).toList());
        assertEquals(List.of(first, second), result.selection().selectedItems());
        assertEquals(2, result.diagnostics().selectedCount());
        assertEquals(2, result.diagnostics().compressedCount());
    }

    @Test
    void optionalEvictionsArePreservedAndNeverCompressed() {
        ContextItem selected = item("selected", 10, false);
        ContextItem evicted = item("evicted", 1, false);
        RecordingCompressor compressor = new RecordingCompressor();
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), compressor);

        ContextProcessingResult result = processor.process(request(8, selected, evicted));

        assertEquals(List.of(selected), result.selection().selectedItems());
        assertEquals(List.of(evicted), result.evictions().stream().map(ContextEviction::item).toList());
        assertEquals(List.of(selected), compressor.seenItems);
        assertEquals(1, result.diagnostics().evictedCount());
    }

    @Test
    void preservesOrderingAndMetadataThroughCompression() {
        ContextItem first = new ContextItem(SECTION, "first  ", 4, true);
        ContextItem second = new ContextItem(SECTION, "second\t", 9, false);
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());

        ContextProcessingResult result = processor.process(request(20, first, second));

        assertEquals(List.of(first, second), result.selection().selectedItems());
        assertEquals(List.of(SECTION, SECTION), result.selectedItems().stream()
                .map(ContextItem::section).toList());
        assertEquals(List.of(4, 9), result.selectedItems().stream()
                .map(ContextItem::priority).toList());
        assertEquals(List.of(true, false), result.selectedItems().stream()
                .map(ContextItem::required).toList());
        assertEquals(List.of("first", "second"), result.selectedItems().stream()
                .map(ContextItem::content).toList());
    }

    @Test
    void requiredOverflowIsPreservedWithoutReselection() {
        ContextItem required = item("12345", 0, true);
        ContextItem optional = item("ok", 10, false);
        RecordingSelector selector = new RecordingSelector(
                new DefaultContextItemSelector(), 1);
        RecordingCompressor compressor = new RecordingCompressor();
        DefaultContextProcessor processor = new DefaultContextProcessor(selector, compressor);

        ContextProcessingResult result = processor.process(request(3, required, optional));

        assertTrue(result.diagnostics().requiredOverflow());
        assertEquals(List.of(required), result.selection().selectedItems());
        assertEquals(List.of(required), result.requiredOverflowItems());
        assertEquals(List.of(required), compressor.seenItems);
        assertEquals(1, selector.calls);
        assertEquals(5L, result.selection().usage(SECTION));
        assertEquals(5L, result.postCompressionUsage(SECTION));
        assertEquals(2L, result.selection().overflow(SECTION));
    }

    @Test
    void reportsConsistentAccounting() {
        ContextItem changed = item("one  ", 1, false);
        ContextItem unchanged = item("two", 2, false);
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());

        ContextProcessingResult result = processor.process(request(20, changed, unchanged));
        ContextProcessingResult.Diagnostics diagnostics = result.diagnostics();

        assertEquals(2, diagnostics.candidateCount());
        assertEquals(2, diagnostics.selectedCount());
        assertEquals(0, diagnostics.evictedCount());
        assertEquals(1, diagnostics.compressedCount());
        assertEquals(8L, diagnostics.originalSelectedCharacters());
        assertEquals(6L, diagnostics.compressedSelectedCharacters());
        assertEquals(2L, diagnostics.charactersSaved());
        assertEquals(6L, result.postCompressionUsage(SECTION));
    }

    @Test
    void emptyInputReturnsEmptyResult() {
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());

        ContextProcessingResult result = processor.process(request(0));

        assertTrue(result.selectedItems().isEmpty());
        assertTrue(result.evictions().isEmpty());
        assertEquals(0, result.diagnostics().candidateCount());
        assertEquals(0, result.diagnostics().selectedCount());
        assertEquals(0, result.diagnostics().evictedCount());
        assertEquals(0, result.diagnostics().compressedCount());
        assertEquals(0L, result.diagnostics().originalSelectedCharacters());
        assertEquals(0L, result.diagnostics().compressedSelectedCharacters());
        assertEquals(0L, result.diagnostics().charactersSaved());
        assertFalse(result.diagnostics().requiredOverflow());
    }

    @Test
    void noOpCompressionIsNotCounted() {
        ContextItem item = item("already normalized", 1, false);
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());

        ContextProcessingResult result = processor.process(request(30, item));

        assertEquals(0, result.diagnostics().compressedCount());
        assertEquals(0L, result.diagnostics().charactersSaved());
        assertFalse(result.compressionResults().get(0).compressionApplied());
    }

    @Test
    void repeatedProcessingIsDeterministic() {
        ContextProcessingRequest request = request(30,
                item("one\r\n", 1, false), item("two  ", 2, false));
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());

        assertEquals(processor.process(request), processor.process(request));
    }

    @Test
    void requestAndResultCollectionsAreImmutableAndInputIsSnapshotted() {
        List<ContextItem> items = new ArrayList<>();
        items.add(item("value", 1, false));
        ContextProcessingRequest request = new ContextProcessingRequest(budget(10), items);
        items.clear();
        DefaultContextProcessor processor = new DefaultContextProcessor(
                new DefaultContextItemSelector(), new DefaultContextCompressor());
        ContextProcessingResult result = processor.process(request);

        assertEquals(1, request.items().size());
        assertThrows(UnsupportedOperationException.class, () -> request.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.selectedItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.evictions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.compressionResults().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.postCompressionUsage().put(SECTION, 0L));
    }

    @Test
    void selectorIsCalledOnceAndCompressorFailuresPropagate() {
        ContextItem selected = item("value", 1, false);
        RecordingSelector selector = new RecordingSelector(new DefaultContextItemSelector(), 1);
        ContextCompressor failingCompressor = item -> {
            throw new IllegalStateException("compression failed");
        };
        DefaultContextProcessor processor = new DefaultContextProcessor(selector, failingCompressor);

        assertThrows(IllegalStateException.class, () -> processor.process(request(10, selected)));
        assertEquals(1, selector.calls);
    }

    private static ContextProcessingRequest request(long amount, ContextItem... items) {
        return new ContextProcessingRequest(budget(amount), List.of(items));
    }

    private static ContextBudget budget(long amount) {
        return new ContextBudget(ContextBudgetUnit.CHARACTERS, amount,
                List.of(new ContextBudgetAllocation(SECTION, amount)));
    }

    private static ContextItem item(String content, int priority, boolean required) {
        return new ContextItem(SECTION, content, priority, required);
    }

    private static final class RecordingCompressor implements ContextCompressor {
        private final List<ContextItem> seenItems = new ArrayList<>();

        @Override
        public ContextCompressionResult compress(ContextItem item) {
            seenItems.add(item);
            return new DefaultContextCompressor().compress(item);
        }
    }

    private static final class RecordingSelector implements ContextItemSelector {
        private final ContextItemSelector delegate;
        private int calls;

        private RecordingSelector(ContextItemSelector delegate, int expectedCalls) {
            this.delegate = delegate;
            assertEquals(1, expectedCalls);
        }

        @Override
        public ContextItemSelectionResult select(ContextBudget budget, List<ContextItem> items) {
            calls++;
            return delegate.select(budget, items);
        }
    }
}
