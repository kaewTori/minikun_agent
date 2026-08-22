package com.minikun.research;

import java.util.Objects;

/** Bounded, deterministic interpretation of research and storytelling intent. */
public record ResearchIntent(
        boolean deepResearch,
        boolean storytellingRequested,
        NarrativeMode narrativeMode) {
    public ResearchIntent {
        Objects.requireNonNull(narrativeMode, "narrative mode must not be null");
    }
}
