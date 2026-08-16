package com.minikun.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.personality.management.PersonaManagementService;
import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import com.minikun.personality.profile.InMemoryUserProfileStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PersonaManagementServiceTest {
    @Test
    void savesAndForgetsOwnerScopedProfileAndPreferences() {
        var service = new PersonaManagementService(new InMemoryUserProfileStore(), new InMemoryPreferenceStore());
        service.saveProfile(new UserProfile("default", "พี่สาว", "th", "concise", "Asia/Bangkok"));
        service.savePreference(new Preference("default", "language", "Thai", .95, Instant.now()));
        service.savePreference(new Preference("other", "language", "English", .95, Instant.now()));

        assertEquals("พี่สาว", service.profile("default").displayName());
        assertEquals(1, service.preferences("default").size());
        assertTrue(service.deletePreference("default", "language"));
        assertEquals(0, service.forget("default"));
        assertEquals(1, service.preferences("other").size());
    }
}
