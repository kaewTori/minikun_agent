package com.minikun.pcs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("minikun.knowledge-selection")
public class KnowledgeSelectionProperties {
    private SourceProperties memory = SourceProperties.unbounded();
    private SourceProperties search = SourceProperties.unbounded();

    public SourceProperties getMemory() {
        return memory;
    }

    public void setMemory(SourceProperties memory) {
        this.memory = memory;
    }

    public SourceProperties getSearch() {
        return search;
    }

    public void setSearch(SourceProperties search) {
        this.search = search;
    }

    public KnowledgeSelectionPolicy toPolicy() {
        return new KnowledgeSelectionPolicy(memory.toPolicy(), search.toPolicy());
    }

    public static class SourceProperties {
        private boolean enabled = true;
        private int maxCandidates = KnowledgeSelectionPolicy.UNBOUNDED;
        private int maxCharacters = KnowledgeSelectionPolicy.UNBOUNDED;

        public static SourceProperties unbounded() {
            return new SourceProperties();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(int maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public int getMaxCharacters() {
            return maxCharacters;
        }

        public void setMaxCharacters(int maxCharacters) {
            this.maxCharacters = maxCharacters;
        }

        private KnowledgeSelectionPolicy.SourcePolicy toPolicy() {
            return new KnowledgeSelectionPolicy.SourcePolicy(enabled, maxCandidates, maxCharacters);
        }
    }
}