package com.minikun.context.selection;

import com.minikun.pcs.KnowledgeSource;

public interface ContextSourcePriorityPolicy {
    int priority(KnowledgeSource source);
}
