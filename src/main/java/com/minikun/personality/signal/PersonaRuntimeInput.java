package com.minikun.personality.signal;

import com.minikun.memory.model.Memory;
import com.minikun.personality.model.MoodSnapshot;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import java.util.List;

public record PersonaRuntimeInput(
        String ownerId,
        String message,
        List<Memory> recalledMemories,
        UserProfile profile,
        List<Preference> preferences,
        boolean toolInvocationRequested,
        boolean toolRequiresConfirmation,
        boolean searchRequested,
        MoodSnapshot mood) {
    public PersonaRuntimeInput {
        ownerId = ownerId == null || ownerId.isBlank() ? "default" : ownerId.trim();
        message = message == null ? "" : message;
        recalledMemories = recalledMemories == null ? List.of() : List.copyOf(recalledMemories);
        profile = profile == null ? UserProfile.EMPTY : profile;
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        mood = mood == null ? MoodSnapshot.DEFAULT : mood;
    }
}
