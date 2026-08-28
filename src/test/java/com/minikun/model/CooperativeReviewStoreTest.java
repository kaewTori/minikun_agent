package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CooperativeReviewStoreTest {
    @Test
    void evictsOldestReviewAtConfiguredLimit() {
        CooperativeReviewStore store = new CooperativeReviewStore(2);

        store.pending("one", "draft");
        store.pending("two", "draft");
        store.completed("three", "draft", "revised");

        assertNull(store.find("one"));
        assertEquals("PENDING", store.find("two").status());
        assertEquals("COMPLETED", store.find("three").status());
    }
}
