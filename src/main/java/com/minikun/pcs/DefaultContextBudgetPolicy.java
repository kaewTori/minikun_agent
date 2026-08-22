package com.minikun.pcs;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultContextBudgetPolicy implements ContextBudgetPolicy {
    private static final Map<ContextBudgetSection, Long> DEFAULT_WEIGHTS = Map.of(
            ContextBudgetSection.CHARACTER, 15L,
            ContextBudgetSection.RUNTIME, 10L,
            ContextBudgetSection.CONVERSATION, 30L,
            ContextBudgetSection.USER_MODEL, 5L,
            ContextBudgetSection.MEMORY, 10L,
            ContextBudgetSection.KNOWLEDGE, 5L,
            ContextBudgetSection.CAPABILITIES, 5L,
            ContextBudgetSection.USER_MESSAGE, 20L);

    private final Map<ContextBudgetSection, Long> weights;
    private final BigInteger totalWeight;

    public DefaultContextBudgetPolicy() {
        this(DEFAULT_WEIGHTS);
    }

    public DefaultContextBudgetPolicy(Map<ContextBudgetSection, Long> weights) {
        Objects.requireNonNull(weights, "weights must not be null");
        if (weights.size() != ContextBudgetSection.values().length) {
            throw new IllegalArgumentException("weights must define every context budget section exactly once");
        }

        EnumMap<ContextBudgetSection, Long> snapshot = new EnumMap<>(ContextBudgetSection.class);
        BigInteger sum = BigInteger.ZERO;
        for (ContextBudgetSection section : ContextBudgetSection.values()) {
            Long weight = weights.get(section);
            if (weight == null) {
                throw new IllegalArgumentException("weight missing for section: " + section);
            }
            if (weight < 0) {
                throw new IllegalArgumentException("weight must not be negative: " + section);
            }
            snapshot.put(section, weight);
            sum = sum.add(BigInteger.valueOf(weight));
        }
        if (sum.signum() <= 0) {
            throw new IllegalArgumentException("total weight must be positive");
        }
        this.weights = Map.copyOf(snapshot);
        this.totalWeight = sum;
    }

    public Map<ContextBudgetSection, Long> weights() {
        return weights;
    }

    @Override
    public ContextBudget allocate(long totalBudget) {
        if (totalBudget < 0) {
            throw new IllegalArgumentException("total budget must not be negative");
        }

        BigInteger total = BigInteger.valueOf(totalBudget);
        Map<ContextBudgetSection, BigInteger> remainders = new EnumMap<>(ContextBudgetSection.class);
        List<ContextBudgetAllocation> allocations = new ArrayList<>(ContextBudgetSection.values().length);
        BigInteger allocated = BigInteger.ZERO;
        for (ContextBudgetSection section : ContextBudgetSection.values()) {
            BigInteger weightedTotal = total.multiply(BigInteger.valueOf(weights.get(section)));
            BigInteger[] quotientAndRemainder = weightedTotal.divideAndRemainder(totalWeight);
            long amount = quotientAndRemainder[0].longValueExact();
            allocations.add(new ContextBudgetAllocation(section, amount));
            allocated = allocated.add(quotientAndRemainder[0]);
            remainders.put(section, quotientAndRemainder[1]);
        }

        int remainderUnits = total.subtract(allocated).intValueExact();
        boolean[] distributed = new boolean[ContextBudgetSection.values().length];
        for (int index = 0; index < remainderUnits; index++) {
            int selectedIndex = selectRemainderSection(remainders, distributed);
            ContextBudgetAllocation current = allocations.get(selectedIndex);
            allocations.set(selectedIndex,
                    new ContextBudgetAllocation(current.section(), current.amount() + 1));
            distributed[selectedIndex] = true;
        }
        return new ContextBudget(ContextBudgetUnit.CHARACTERS, totalBudget, allocations);
    }

    private int selectRemainderSection(
            Map<ContextBudgetSection, BigInteger> remainders,
            boolean[] distributed) {
        int selectedIndex = -1;
        BigInteger selectedRemainder = BigInteger.valueOf(-1);
        ContextBudgetSection[] sections = ContextBudgetSection.values();
        for (int index = 0; index < sections.length; index++) {
            if (!distributed[index]) {
                BigInteger remainder = remainders.get(sections[index]);
                if (remainder.compareTo(selectedRemainder) > 0) {
                    selectedIndex = index;
                    selectedRemainder = remainder;
                }
            }
        }
        return selectedIndex;
    }
}
