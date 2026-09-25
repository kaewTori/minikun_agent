package com.minikun.pcs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DefaultContextItemSelector implements ContextItemSelector {
    private static final Comparator<IndexedItem> OPTIONAL_ORDER = Comparator
            .comparingInt((IndexedItem indexed) -> indexed.item().priority())
            .reversed()
            .thenComparingInt(IndexedItem::index);

    @Override
    public ContextItemSelectionResult select(ContextBudget budget, List<ContextItem> items) {
        Objects.requireNonNull(budget, "budget must not be null");
        if (budget.unit() != ContextBudgetUnit.CHARACTERS) {
            throw new IllegalArgumentException("context item selection requires character budgets");
        }
        Objects.requireNonNull(items, "items must not be null");
        List<ContextItem> input = List.copyOf(items);
        List<IndexedItem> indexedItems = indexItems(input);
        List<ContextItem> requiredItems = indexedItems.stream()
                .filter(indexed -> indexed.item().required())
                .map(IndexedItem::item)
                .toList();

        EnumMap<ContextBudgetSection, Long> usage = usageBySection(requiredItems);
        EnumMap<ContextBudgetSection, Long> limits = effectiveAllocations(budget, input, usage);
        List<ContextItem> requiredOverflowItems = requiredOverflowItems(requiredItems, usage, limits);
        if (!requiredOverflowItems.isEmpty()) {
            List<ContextEviction> evictions = indexedItems.stream()
                    .filter(indexed -> !indexed.item().required())
                    .map(indexed -> new ContextEviction(indexed.item(), ContextEvictionReason.OVER_BUDGET))
                    .toList();
            return result(requiredItems, evictions, requiredOverflowItems, usage, limits, true);
        }

        Set<ContextItem> selected = new HashSet<>(requiredItems);
        List<ContextEviction> evictions = new ArrayList<>();
        for (IndexedItem indexed : indexedItems.stream()
                .filter(candidate -> !candidate.item().required())
                .sorted(OPTIONAL_ORDER)
                .toList()) {
            ContextItem item = indexed.item();
            long available = limits.get(item.section()) - usage.getOrDefault(item.section(), 0L);
            if (item.size() <= available) {
                selected.add(item);
                usage.merge(item.section(), (long) item.size(), Math::addExact);
            } else {
                evictions.add(new ContextEviction(item, ContextEvictionReason.OVER_BUDGET));
            }
        }

        List<ContextItem> selectedInInputOrder = input.stream()
                .filter(selected::contains)
                .toList();
        return result(selectedInInputOrder, evictions, List.of(), usage, limits, false);
    }

    private List<IndexedItem> indexItems(List<ContextItem> items) {
        List<IndexedItem> indexedItems = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            indexedItems.add(new IndexedItem(index, Objects.requireNonNull(items.get(index), "item must not be null")));
        }
        return List.copyOf(indexedItems);
    }

    private EnumMap<ContextBudgetSection, Long> usageBySection(List<ContextItem> items) {
        EnumMap<ContextBudgetSection, Long> usage = new EnumMap<>(ContextBudgetSection.class);
        for (ContextItem item : items) {
            usage.merge(item.section(), (long) item.size(), Math::addExact);
        }
        return usage;
    }

    private EnumMap<ContextBudgetSection, Long> effectiveAllocations(
            ContextBudget budget,
            List<ContextItem> items,
            EnumMap<ContextBudgetSection, Long> requiredUsage) {
        EnumMap<ContextBudgetSection, Long> limits = new EnumMap<>(ContextBudgetSection.class);
        for (ContextBudgetSection section : ContextBudgetSection.values()) {
            limits.put(section, budget.allocation(section));
        }
        EnumMap<ContextBudgetSection, Long> demand = usageBySection(items);
        long unallocated = budget.remaining();
        for (ContextBudgetSection target : ContextBudgetSection.values()) {
            long shortage = Math.max(0, requiredUsage.getOrDefault(target, 0L) - limits.get(target));
            long extra = Math.min(shortage, unallocated);
            limits.put(target, limits.get(target) + extra);
            unallocated -= extra;
            shortage -= extra;
            // Borrow unused quotas first, then optional quotas if required context still needs room.
            for (int pass = 0; pass < 2 && shortage > 0; pass++) {
                for (ContextBudgetSection donor : ContextBudgetSection.values()) {
                    if (donor == target) continue;
                    long floor = pass == 0
                            ? Math.min(limits.get(donor), demand.getOrDefault(donor, 0L))
                            : requiredUsage.getOrDefault(donor, 0L);
                    long borrowed = Math.min(shortage, Math.max(0, limits.get(donor) - floor));
                    limits.put(donor, limits.get(donor) - borrowed);
                    limits.put(target, limits.get(target) + borrowed);
                    shortage -= borrowed;
                    if (shortage == 0) break;
                }
            }
        }
        return limits;
    }

    private List<ContextItem> requiredOverflowItems(
            List<ContextItem> requiredItems,
            EnumMap<ContextBudgetSection, Long> usage,
            EnumMap<ContextBudgetSection, Long> limits) {
        Set<ContextBudgetSection> overflowingSections = usage.entrySet().stream()
                .filter(entry -> entry.getValue() > limits.get(entry.getKey()))
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        return requiredItems.stream()
                .filter(item -> overflowingSections.contains(item.section()))
                .toList();
    }

    private ContextItemSelectionResult result(
            List<ContextItem> selectedItems,
            List<ContextEviction> evictions,
            List<ContextItem> requiredOverflowItems,
            EnumMap<ContextBudgetSection, Long> usage,
            EnumMap<ContextBudgetSection, Long> limits,
            boolean requiredOverflow) {
        EnumMap<ContextBudgetSection, Long> remaining = new EnumMap<>(ContextBudgetSection.class);
        EnumMap<ContextBudgetSection, Long> overflow = new EnumMap<>(ContextBudgetSection.class);
        for (ContextBudgetSection section : ContextBudgetSection.values()) {
            long selectedUsage = usage.getOrDefault(section, 0L);
            long allocation = limits.get(section);
            remaining.put(section, Math.max(allocation - selectedUsage, 0L));
            overflow.put(section, Math.max(selectedUsage - allocation, 0L));
        }
        return new ContextItemSelectionResult(
                selectedItems, evictions, requiredOverflowItems, usage, remaining, overflow, requiredOverflow);
    }

    private record IndexedItem(int index, ContextItem item) {
    }
}
