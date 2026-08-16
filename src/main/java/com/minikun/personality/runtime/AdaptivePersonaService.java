package com.minikun.personality.runtime;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;
import com.minikun.personality.model.MoodSnapshot;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.preference.PreferenceStore;
import com.minikun.personality.profile.UserProfileStore;
import com.minikun.personality.signal.PersonaRuntimeInput;
import java.util.List;
import org.springframework.stereotype.Service;

/** Application facade for one user's adaptive persona state. */
@Service
public final class AdaptivePersonaService {
    private final AdaptivePersonaRuntime runtime;
    private final MemoryRepository memoryRepository;
    private final UserProfileStore profileStore;
    private final PreferenceStore preferenceStore;

    public AdaptivePersonaService(AdaptivePersonaRuntime runtime, MemoryRepository memoryRepository,
            UserProfileStore profileStore, PreferenceStore preferenceStore) {
        this.runtime = runtime;
        this.memoryRepository = memoryRepository;
        this.profileStore = profileStore;
        this.preferenceStore = preferenceStore;
    }

    public AdaptivePersonaResult evaluate(String ownerId, String message, boolean searchRequested,
            boolean toolRequested, boolean toolRequiresConfirmation, MoodSnapshot mood) {
        String owner = ownerId == null || ownerId.isBlank() ? "default" : ownerId;
        List<Memory> memories = safeFindMemories(owner);
        UserProfile profile = profileStore.find(owner);
        List<Preference> preferences = preferenceStore.findByOwner(owner);
        return runtime.evaluate(new PersonaRuntimeInput(owner, message, memories, profile, preferences,
                toolRequested, toolRequiresConfirmation, searchRequested, mood));
    }

    private List<Memory> safeFindMemories(String owner) {
        try { return memoryRepository.findByOwner(owner, 20); }
        catch (UnsupportedOperationException ignored) { return List.of(); }
    }
}
