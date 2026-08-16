package com.minikun.context.selection;

import com.minikun.pcs.KnowledgeSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultContextSourcePriorityPolicyTest {
    @Test
    void keepsMemoryAboveSearchAndBrowserByDefault() {
        ContextSourcePriorityPolicy policy = new DefaultContextSourcePriorityPolicy();

        assertEquals(3, policy.priority(KnowledgeSource.MEMORY));
        assertEquals(2, policy.priority(KnowledgeSource.SEARCH));
        assertEquals(1, policy.priority(KnowledgeSource.BROWSER));
    }
}
