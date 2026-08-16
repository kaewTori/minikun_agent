package com.minikun.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.personality.model.Mood;
import com.minikun.personality.model.MoodSnapshot;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.runtime.AdaptivePersonaRuntime;
import com.minikun.personality.signal.PersonaRuntimeInput;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdaptivePersonaRuntimeTest {
    @Test
    void projectsMemoryProfilePreferenceToolSearchAndMoodIntoDeterministicOverlays() {
        Memory memory = new Memory("default", "c1", MemoryId.generate(), MemoryCategory.PREFERENCE,
                MemorySource.LLM_EXTRACTION, "prefers Thai", Instant.now(), .9, "test");
        Preference preference = new Preference("default", "language", "Thai", .9, Instant.now());
        PersonaRuntimeInput input = new PersonaRuntimeInput("default", "ช่วยวางแผน", List.of(memory),
                new UserProfile("default", "พี่สาว", "th", "concise", "Asia/Bangkok"),
                List.of(preference), true, true, true,
                new MoodSnapshot(Mood.FOCUSED, .8, Instant.now(), "planning"));

        var result = new AdaptivePersonaRuntime().evaluate(input);

        assertEquals(1, result.signals().recalledMemoryCount());
        assertTrue(result.activation().overlays().containsAll(List.of(
                "memory-aware", "user-profile", "user-preferences", "tool-aware", "search-aware", "mood-focused")));
        assertTrue(result.activation().reasons().contains("tool requires confirmation"));
    }

    @Test
    void neverActivatesMoodOverlayForDefaultMood() {
        var result = new AdaptivePersonaRuntime().evaluate(new PersonaRuntimeInput(
                "default", "hello", List.of(), UserProfile.EMPTY, List.of(), false, false, false,
                MoodSnapshot.DEFAULT));

        assertTrue(result.activation().overlays().isEmpty());
    }
}
