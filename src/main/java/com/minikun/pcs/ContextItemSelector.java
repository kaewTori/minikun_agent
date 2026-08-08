package com.minikun.pcs;

import java.util.List;

public interface ContextItemSelector {
    ContextItemSelectionResult select(ContextBudget budget, List<ContextItem> items);
}