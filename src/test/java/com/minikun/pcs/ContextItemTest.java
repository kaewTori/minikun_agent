package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextItemTest {
    @Test
    void validItemsExposeDeterministicCharacterSizeAndRequiredState() {
        ContextItem required = new ContextItem(ContextBudgetSection.CHARACTER, "identity", 4, true);
        ContextItem optional = new ContextItem(ContextBudgetSection.MEMORY, "memory", 2, false);

        assertEquals(8, required.size());
        assertTrue(required.required());
        assertEquals(6, optional.size());
        assertFalse(optional.required());
    }

    @Test
    void invalidItemsAreRejected() {
        assertThrows(NullPointerException.class, () -> new ContextItem(null, "content", 1, false));
        assertThrows(NullPointerException.class,
                () -> new ContextItem(ContextBudgetSection.RUNTIME, null, 1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextItem(ContextBudgetSection.RUNTIME, "content", -1, false));
    }

    @Test
    void emptyContentIsValidAndSourceTextCannotChangeItemState() {
        StringBuilder source = new StringBuilder("content");
        ContextItem item = new ContextItem(ContextBudgetSection.RUNTIME, source.toString(), 0, false);
        source.replace(0, source.length(), "changed");

        assertEquals("content", item.content());
        assertEquals(7, item.size());
        assertEquals(0, new ContextItem(ContextBudgetSection.RUNTIME, "", 0, false).size());
    }
}