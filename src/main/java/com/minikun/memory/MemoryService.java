package com.minikun.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.AcceptedMemory;
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
                    candidate.content().trim(), Instant.now(clock), candidate.confidence(), candidate.reason().trim());
                AcceptedMemory request = new AcceptedMemory(
                    conversation.conversationId(), candidate.category(), MemorySource.LLM_EXTRACTION,
                    candidate.content().trim(), candidate.confidence(), candidate.reason().trim());
                return repository.save(request)
                    ? memory : null;
        }).filter(java.util.Objects::nonNull).toList();
    }

}
