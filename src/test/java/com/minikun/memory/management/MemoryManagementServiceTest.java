package com.minikun.memory.management;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryUpdate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagementServiceTest {
    @Test
    void listsAndDeletesOnlyThroughOwnerScopedRepositoryOperations() {
        InMemoryRepository repository = new InMemoryRepository();
        MemoryManagementService service = new MemoryManagementService(repository);
        MemoryId id = MemoryId.generate();
        repository.memories.add(new Memory(
                "default", id, MemoryCategory.PREFERENCE, MemorySource.LLM_EXTRACTION,
                "likes tea", Instant.now(), 0.9, "test"));

        assertEquals(1, service.list("default", 10).size());
        assertEquals(List.of("default"), repository.listOwners);
        assertEquals(true, service.delete("default", id));
        assertEquals("default", repository.deletedOwner);
        assertEquals(id, repository.deletedId);
    }

    @Test
    void rejectsWildcardAndExcessiveLimits() {
        MemoryManagementService service = new MemoryManagementService(new InMemoryRepository());

        assertThrows(IllegalArgumentException.class, () -> service.list("*", 10));
        assertThrows(IllegalArgumentException.class, () -> service.list("default", 501));
    }

    @Test
    void savesExplicitMemoryAsUserDirectiveWithOwnerScope() {
        InMemoryRepository repository = new InMemoryRepository();
        MemoryManagementService service = new MemoryManagementService(repository);

        assertEquals(true, service.remember("default", "conversation", MemoryCategory.PREFERENCE,
                "ไม่กินเผ็ด"));
        assertEquals("default", repository.saved.ownerId());
        assertEquals("conversation", repository.saved.conversationId());
        assertEquals(MemoryCategory.PREFERENCE, repository.saved.category());
        assertEquals(MemorySource.USER_DIRECTIVE, repository.saved.source());
    }

    @Test
    void updatesMemoryOnlyWithinTheRequestedOwnerScope() {
        InMemoryRepository repository = new InMemoryRepository();
        MemoryManagementService service = new MemoryManagementService(repository);
        MemoryId id = MemoryId.generate();

        assertTrue(service.update("default", id,
                new MemoryUpdate(MemoryCategory.PROFILE, "อยู่เชียงใหม่", 1.0, "user correction")));
        assertEquals("default", repository.updatedOwner);
        assertEquals(id, repository.updatedId);
        assertEquals("อยู่เชียงใหม่", repository.updated.content());
    }

    private static final class InMemoryRepository implements MemoryRepository {
        private final List<Memory> memories = new ArrayList<>();
        private final List<String> listOwners = new ArrayList<>();
        private String deletedOwner;
        private MemoryId deletedId;
        private AcceptedMemory saved;
        private String updatedOwner;
        private MemoryId updatedId;
        private MemoryUpdate updated;

        @Override
        public boolean save(com.minikun.memory.model.AcceptedMemory memory) {
            saved = memory;
            return true;
        }

        @Override
        public List<Memory> findByOwner(String ownerId, int limit) {
            listOwners.add(ownerId);
            return memories.stream().filter(memory -> ownerId.equals(memory.ownerId())).limit(limit).toList();
        }

        @Override
        public boolean deleteByOwner(String ownerId, MemoryId memoryId) {
            deletedOwner = ownerId;
            deletedId = memoryId;
            return true;
        }

        @Override
        public boolean updateByOwner(String ownerId, MemoryId memoryId, MemoryUpdate update) {
            updatedOwner = ownerId;
            updatedId = memoryId;
            updated = update;
            return true;
        }
    }
}
