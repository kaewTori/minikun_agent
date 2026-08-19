package com.minikun.personality.profile;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;
import com.minikun.personality.model.PersonalUserModel;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.preference.PreferenceStore;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Builds the owner-scoped user model and applies its lifecycle policy.
 * Profile/preferences/memory remain stored in their existing stores.
 */
@Service
@ConditionalOnBean(MemoryRepository.class)
public final class UserModelService {
    private final MemoryRepository memoryRepository;
    private final UserProfileStore profileStore;
    private final PreferenceStore preferenceStore;
    private final Clock clock;
    private final int maximumMemories;
    private final Duration memoryMaxAge;
    private final Duration preferenceMaxAge;
    private final double minimumConfidence;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TaskService taskService;

    public UserModelService(
            MemoryRepository memoryRepository,
            UserProfileStore profileStore,
            PreferenceStore preferenceStore,
            Clock clock,
            @Value("${minikun.user-model.maximum-memories:20}") int maximumMemories,
            @Value("${minikun.user-model.memory-max-age:365d}") Duration memoryMaxAge,
            @Value("${minikun.user-model.preference-max-age:365d}") Duration preferenceMaxAge,
            @Value("${minikun.user-model.minimum-confidence:0.5}") double minimumConfidence) {
        this.memoryRepository = Objects.requireNonNull(memoryRepository, "memory repository must not be null");
        this.profileStore = Objects.requireNonNull(profileStore, "profile store must not be null");
        this.preferenceStore = Objects.requireNonNull(preferenceStore, "preference store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maximumMemories < 0) {
            throw new IllegalArgumentException("maximum memories must not be negative");
        }
        if (memoryMaxAge.isNegative() || memoryMaxAge.isZero()
                || preferenceMaxAge.isNegative() || preferenceMaxAge.isZero()) {
            throw new IllegalArgumentException("user model max ages must be positive");
        }
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("minimum confidence must be between 0 and 1");
        }
        this.maximumMemories = maximumMemories;
        this.memoryMaxAge = memoryMaxAge;
        this.preferenceMaxAge = preferenceMaxAge;
        this.minimumConfidence = minimumConfidence;
    }

    public PersonalUserModel snapshot(String ownerId) {
        String owner = normalizeOwner(ownerId);
        Instant now = clock.instant();
        UserProfile profile = profileStore.find(owner);
        List<Preference> preferences = preferenceStore.findByOwner(owner).stream()
                .filter(preference -> preference.activeAt(now, preferenceMaxAge, minimumConfidence))
                .sorted(java.util.Comparator.comparing(Preference::key))
                .toList();
        List<Memory> memories = memoryRepository.findByOwner(owner, maximumMemories).stream()
                .filter(memory -> memory.confidence() >= minimumConfidence)
                .filter(memory -> !memory.createdAt().plus(memoryMaxAge).isBefore(now))
                .toList();
        List<PersonalTask> tasks = taskService == null ? List.of() : taskService.list(owner, null).stream()
                .filter(PersonalTask::active)
                .limit(maximumMemories)
                .toList();
        return new PersonalUserModel(owner, profile, preferences, memories, tasks, now);
    }

    /** Clears all user-model sources while preserving the owner record. */
    public ForgetResult forget(String ownerId) {
        String owner = normalizeOwner(ownerId);
        int preferences = preferenceStore.deleteAll(owner);
        int memories = memoryRepository.deleteAllByOwner(owner);
        profileStore.save(new UserProfile(owner, "", "", "", ""));
        return new ForgetResult(owner, preferences, memories, true);
    }

    private String normalizeOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        return ownerId.trim();
    }

    public record ForgetResult(String ownerId, int deletedPreferences, int deletedMemories,
            boolean profileCleared) {
    }
}
