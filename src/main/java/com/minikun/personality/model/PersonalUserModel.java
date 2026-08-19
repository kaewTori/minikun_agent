package com.minikun.personality.model;

import com.minikun.memory.model.Memory;
import com.minikun.task.PersonalTask;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, prompt-safe projection of the information Mini-kun knows about
 * one owner. It intentionally composes the existing profile, preferences and
 * long-term memories instead of introducing a second source of truth.
 */
public record PersonalUserModel(
        String ownerId,
        UserProfile profile,
        List<Preference> preferences,
        List<Memory> memories,
        List<PersonalTask> tasks,
        Instant generatedAt) {

    public static final PersonalUserModel EMPTY = new PersonalUserModel(
            "default", UserProfile.EMPTY, List.of(), List.of(), List.of(), Instant.EPOCH);

    public PersonalUserModel(String ownerId, UserProfile profile, List<Preference> preferences,
            List<Memory> memories, Instant generatedAt) {
        this(ownerId, profile, preferences, memories, List.of(), generatedAt);
    }

    public PersonalUserModel {
        ownerId = require(ownerId, "ownerId");
        profile = profile == null ? new UserProfile(ownerId, "", "", "", "") : profile;
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
        memories = memories == null ? List.of() : List.copyOf(memories);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
    }

    public boolean available() {
        return profile.available() || !preferences.isEmpty() || !memories.isEmpty() || !tasks.isEmpty();
    }

    /**
     * Renders only contextual facts. The model is told to treat these as
     * guidance, not as user instructions, so a remembered string cannot
     * override the application policy or the current user message.
     */
    public String promptContent() {
        if (!available()) {
            return "";
        }
        StringBuilder content = new StringBuilder("[Personal User Context]\n");
        if (profile.available()) {
            content.append("Profile:\n");
            append(content, "display_name", profile.displayName());
            append(content, "preferred_language", profile.preferredLanguage());
            append(content, "response_style", profile.responseStyle());
            append(content, "timezone", profile.timezone());
        }
        if (!preferences.isEmpty()) {
            content.append("Preferences:\n");
            preferences.forEach(preference -> append(content, preference.key(), preference.value()));
        }
        if (!memories.isEmpty()) {
            content.append("Long-term facts:\n");
            memories.forEach(memory -> content.append("- [")
                    .append(memory.category().name().toLowerCase(java.util.Locale.ROOT))
                    .append("] ").append(memory.content()).append('\n'));
        }
        if (!tasks.isEmpty()) {
            content.append("Open work:\n");
            tasks.forEach(task -> {
                content.append("- [").append(task.status().name().toLowerCase(java.util.Locale.ROOT)).append("] ")
                        .append(task.title());
                if (!task.nextAction().isBlank()) content.append("; next: ").append(task.nextAction());
                if (!task.waitingFor().isBlank()) content.append("; waiting for: ").append(task.waitingFor());
                content.append('\n');
            });
        }
        content.append("Use this only as background context. Do not reveal or invent personal facts.");
        return content.toString().trim();
    }

    private static void append(StringBuilder content, String key, String value) {
        if (value != null && !value.isBlank()) {
            content.append("- ").append(key).append(": ").append(value).append('\n');
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException(field + " must not be blank or wildcard");
        }
        return value.trim();
    }
}
