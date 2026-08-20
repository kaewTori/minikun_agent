package com.minikun.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class KnowledgeRootTest {
    @Test
    void parsesNamedAbsoluteRoots() {
        var roots = KnowledgeRoot.parseList("notes=/tmp/notes,work=/tmp/work");
        assertEquals(2, roots.size());
        assertEquals("notes", roots.getFirst().name());
    }

    @Test
    void rejectsRelativeAndDuplicateRoots() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeRoot.parseList("notes=relative"));
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeRoot.parseList("notes=/tmp/one,NOTES=/tmp/two"));
    }
}
