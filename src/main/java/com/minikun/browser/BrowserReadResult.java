package com.minikun.browser;

import com.minikun.pcs.KnowledgeCandidate;
import java.util.List;

/** Result of a best-effort multi-URL browser read. */
public record BrowserReadResult(
        List<KnowledgeCandidate> candidates,
        List<BrowserReadFailure> failures) {
    public BrowserReadResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
