package com.minikun.personality.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import com.minikun.personality.learning.InMemoryAdaptationSignalStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserModelServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void buildsOwnerScopedModelAndDropsExpiredOrLowConfidenceFacts() {
        var profiles = new InMemoryUserProfileStore();
        profiles.save(new UserProfile("owner-a", "พี่", "th", "concise", "Asia/Bangkok"));
        var preferences = new InMemoryPreferenceStore();
        preferences.save(new Preference("owner-a", "language", "Thai", .9, NOW));
        preferences.save(new Preference("owner-a", "old", "ignore", .9, NOW.minusSeconds(366L * 24 * 60 * 60)));
        preferences.save(new Preference("owner-a", "weak", "ignore", .2, NOW));
        preferences.save(new Preference("owner-b", "language", "English", .9, NOW));

        var repository = new FakeMemoryRepository(List.of(
                memory("owner-a", "recent", NOW.minusSeconds(60), .9),
                memory("owner-a", "weak", NOW.minusSeconds(60), .2),
                memory("owner-a", "old", NOW.minusSeconds(366L * 24 * 60 * 60), .9),
                memory("owner-b", "other", NOW.minusSeconds(60), .9)));
        var service = new UserModelService(repository, profiles, preferences,
                Clock.fixed(NOW, ZoneOffset.UTC), 20,
                java.time.Duration.ofDays(365), java.time.Duration.ofDays(365), .5);

        var model = service.snapshot("owner-a");

        assertEquals("พี่", model.profile().displayName());
        assertEquals(List.of("language"), model.preferences().stream().map(Preference::key).toList());
        assertEquals(List.of("recent"), model.memories().stream().map(Memory::content).toList());
        assertTrue(model.promptContent().contains("Thai"));
        assertTrue(!model.promptContent().contains("ignore"));
    }

    @Test
    void forgetClearsAllUserModelSourcesWithoutTouchingAnotherOwner() {
        var profiles = new InMemoryUserProfileStore();
        profiles.save(new UserProfile("owner-a", "พี่", "th", "concise", "Asia/Bangkok"));
        var preferences = new InMemoryPreferenceStore();
        preferences.save(new Preference("owner-a", "language", "Thai", .9, NOW));
        var repository = new FakeMemoryRepository(new ArrayList<>(List.of(
                memory("owner-a", "remove", NOW, .9), memory("owner-b", "keep", NOW, .9))));
        var service = new UserModelService(repository, profiles, preferences, Clock.fixed(NOW, ZoneOffset.UTC),
                20, java.time.Duration.ofDays(365), java.time.Duration.ofDays(365), .5);
        var adaptationSignals = new InMemoryAdaptationSignalStore();
        adaptationSignals.record("owner-a", "language", "th", 1, true, .25, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "adaptationSignalStore", adaptationSignals);

        var result = service.forget("owner-a");

        assertEquals(1, result.deletedPreferences());
        assertEquals(1, result.deletedMemories());
        assertEquals(1, result.deletedAdaptationSignals());
        assertTrue(adaptationSignals.findByOwner("owner-a").isEmpty());
        assertTrue(!profiles.find("owner-a").available());
        assertEquals(1, repository.findByOwner("owner-b", 20).size());
    }

    private static Memory memory(String owner, String content, Instant createdAt, double confidence) {
        return new Memory(owner, "conversation", MemoryId.generate(), MemoryCategory.PROFILE,
                MemorySource.LLM_EXTRACTION, content, createdAt, confidence, "test");
    }

    private static final class FakeMemoryRepository implements MemoryRepository {
        private final List<Memory> values;

        private FakeMemoryRepository(List<Memory> values) {
            this.values = new ArrayList<>(values);
        }

        @Override
        public boolean save(AcceptedMemory memory) { return true; }

        @Override
        public List<Memory> findByOwner(String ownerId, int limit) {
            return values.stream().filter(memory -> ownerId.equals(memory.ownerId())).limit(limit).toList();
        }

        @Override
        public int deleteAllByOwner(String ownerId) {
            int before = values.size();
            values.removeIf(memory -> ownerId.equals(memory.ownerId()));
            return before - values.size();
        }
    }
}
