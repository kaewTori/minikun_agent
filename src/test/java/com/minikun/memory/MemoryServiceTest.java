package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;

class MemoryServiceTest {
    @Test
    void persistsOnlyNewFingerprintsAndGeneratesJavaIds() {
        List<String> fingerprints = new ArrayList<>();
        MemoryRepository repository = request -> {
            String fingerprint = request.conversationId() + "\u0000" + request.category()
                    + "\u0000" + request.content().trim().replaceAll("\\s+", " ").toLowerCase();
            if (fingerprints.contains(fingerprint)) {
                return false;
            }
            fingerprints.add(fingerprint);
            return true;
        };
        MemoryService service = new MemoryService(repository,
                Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        CompletedConversation conversation = new CompletedConversation("conversation-1", List.of());
        CandidateMemory candidate = new CandidateMemory(MemoryCategory.PROFILE, "Lives in Bangkok", 1.0, "explicit");

        List<Memory> first = service.persist(conversation, List.of(candidate));
        List<Memory> second = service.persist(conversation, List.of(candidate));

        assertEquals(1, first.size());
        assertEquals(0, second.size());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), first.getFirst().createdAt());
        assertEquals(1.0, first.getFirst().confidence());
        assertEquals("explicit", first.getFirst().reason());
    }
}
