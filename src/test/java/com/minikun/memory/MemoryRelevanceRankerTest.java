package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryRelevanceRankerTest {
    @Test
    void prefersQueryRelevantMemoryBeforeNewerUnrelatedMemory() {
        Instant now = Instant.now();
        Memory relevant = new Memory("default", "a", MemoryId.generate(), MemoryCategory.PROJECT,
                MemorySource.LLM_EXTRACTION, "Minikun Java architecture", now.minusSeconds(100), .8, "test");
        Memory unrelated = new Memory("default", "b", MemoryId.generate(), MemoryCategory.PREFERENCE,
                MemorySource.LLM_EXTRACTION, "ชอบกาแฟ", now, .9, "test");

        assertEquals(relevant, MemoryRelevanceRanker.rank(List.of(unrelated, relevant), "Java", 1).getFirst());
    }
}
