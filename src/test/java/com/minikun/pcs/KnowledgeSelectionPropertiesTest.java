package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeSelectionPropertiesTest {
    @Test
    void defaultsCreateAnUnboundedEnabledPolicy() {
        KnowledgeSelectionPolicy policy = new KnowledgeSelectionProperties().toPolicy();

        assertEquals(KnowledgeSelectionPolicy.DEFAULT, policy);
    }

    @Test
    void configuredSourcesRemainIndependent() {
        KnowledgeSelectionProperties properties = new KnowledgeSelectionProperties();
        KnowledgeSelectionProperties.SourceProperties memory =
                new KnowledgeSelectionProperties.SourceProperties();
        memory.setEnabled(false);
        memory.setMaxCandidates(2);
        memory.setMaxCharacters(10);
        KnowledgeSelectionProperties.SourceProperties search =
                new KnowledgeSelectionProperties.SourceProperties();
        search.setMaxCandidates(3);
        search.setMaxCharacters(20);
        properties.setMemory(memory);
        properties.setSearch(search);

        KnowledgeSelectionPolicy policy = properties.toPolicy();

        assertEquals(new KnowledgeSelectionPolicy.SourcePolicy(false, 2, 10), policy.memory());
        assertEquals(new KnowledgeSelectionPolicy.SourcePolicy(true, 3, 20), policy.search());
    }

    @Test
    void negativeConfiguredValuesFailWhenPolicyIsCreated() {
        KnowledgeSelectionProperties properties = new KnowledgeSelectionProperties();
        properties.getMemory().setMaxCharacters(-1);

        assertThrows(IllegalArgumentException.class, properties::toPolicy);
    }
}