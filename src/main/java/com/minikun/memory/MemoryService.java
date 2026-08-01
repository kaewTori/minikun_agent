package com.minikun.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;

@Service
public class MemoryService {
    private final MemoryRepository repository;
    private final Clock clock;

    public MemoryService(MemoryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public MemoryService(MemoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Memory> persist(CompletedConversation conversation, List<CandidateMemory> candidates) {
        return candidates.stream().map(candidate -> {
            Memory memory = new Memory(
                    MemoryId.generate(), candidate.category(), MemorySource.LLM_EXTRACTION,
                    candidate.content().trim(), Instant.now(clock));
            return repository.save(memory, fingerprint(conversation, candidate), conversation.conversationId())
                    ? memory : null;
        }).filter(java.util.Objects::nonNull).toList();
    }

    private String fingerprint(CompletedConversation conversation, CandidateMemory candidate) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest((conversation.conversationId() + "\u0000" + candidate.category()
                        + "\u0000" + normalize(candidate.content()))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new MemoryException("SHA-256 fingerprint algorithm is unavailable", exception);
        }
    }

    private String normalize(String content) {
        return content.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
