package com.minikun.memory;

import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;

@Slf4j
public class MemoryAnalyzer {
    private final MemoryExtractionClient extractionClient;
    private final MemoryPolicy policy;

    public MemoryAnalyzer(MemoryExtractionClient extractionClient, MemoryPolicy policy) {
        this.extractionClient = extractionClient;
        this.policy = policy;
    }

    public List<CandidateMemory> analyze(CompletedConversation conversation) {
        long started = System.nanoTime();
        try {
            List<CandidateMemory> candidates = extractionClient.extractConversationMemories(conversation);
            Set<String> seen = new HashSet<>();
            List<CandidateMemory> accepted = candidates.stream()
                    .filter(this::valid)
                    .filter(policy::accepts)
                    .filter(candidate -> seen.add(fingerprint(candidate)))
                    .toList();
            log.info("memory_extraction conversation_id={} duration_ms={} candidate_count={} accepted_count={} rejected_count={} extraction_success=true",
                    conversation.conversationId(), elapsedMillis(started), candidates.size(), accepted.size(),
                    candidates.size() - accepted.size());
            return accepted;
        } catch (RuntimeException exception) {
            log.warn("memory_extraction conversation_id={} duration_ms={} extraction_success=false",
                    conversation.conversationId(), elapsedMillis(started), exception);
            throw exception;
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String fingerprint(CandidateMemory candidate) {
        return candidate.category() + "\u0000" + candidate.content().trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean valid(CandidateMemory candidate) {
        return candidate.content() != null
                && !candidate.content().isBlank()
                && candidate.content().length() <= policy.maximumContentLength()
                && Double.isFinite(candidate.confidence())
                && candidate.confidence() >= 0.0
                && candidate.confidence() <= 1.0
                && !candidate.reason().isBlank();
    }
}
