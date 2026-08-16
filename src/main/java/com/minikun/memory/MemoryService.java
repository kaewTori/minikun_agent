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
    private final ReflectionDecisionService decisionService;
    private final Clock clock;

    public MemoryService(MemoryRepository repository, ReflectionDecisionService decisionService) {
        this(repository, decisionService, Clock.systemUTC());
    }

    public MemoryService(MemoryRepository repository, ReflectionDecisionService decisionService, Clock clock) {
        this.repository = repository;
        this.decisionService = decisionService;
        this.clock = clock;
    }

    public List<Memory> persist(CompletedConversation conversation, List<CandidateMemory> candidates) {
        List<AcceptedMemory> accepted = decisionService.decide(candidates.stream()
            .map(candidate -> new com.minikun.memory.model.MemoryCandidate(
                conversation.conversationId(), candidate.category(), candidate.content().trim(),
                candidate.confidence(), candidate.reason().trim()))
            .toList(), conversation.ownerId());
        return accepted.stream().map(request -> {
            Memory memory = new Memory(
                request.ownerId(), request.conversationId(), MemoryId.generate(), request.category(), request.source(),
                request.content(), Instant.now(clock), request.confidence(), request.reason());
                return repository.save(request)
                    ? memory : null;
        }).filter(java.util.Objects::nonNull).toList();
    }

}
