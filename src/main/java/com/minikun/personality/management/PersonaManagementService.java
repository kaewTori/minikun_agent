package com.minikun.personality.management;

import com.minikun.personality.model.Preference;
import com.minikun.personality.model.UserProfile;
import com.minikun.personality.preference.PreferenceStore;
import com.minikun.personality.profile.UserProfileStore;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class PersonaManagementService {
    private final UserProfileStore profiles;
    private final PreferenceStore preferences;

    public PersonaManagementService(UserProfileStore profiles, PreferenceStore preferences) {
        this.profiles = Objects.requireNonNull(profiles);
        this.preferences = Objects.requireNonNull(preferences);
    }

    public UserProfile profile(String ownerId) { return profiles.find(owner(ownerId)); }
    public void saveProfile(UserProfile profile) { profiles.save(profile); }
    public List<Preference> preferences(String ownerId) { return preferences.findByOwner(owner(ownerId)); }
    public void savePreference(Preference preference) { preferences.save(preference); }
    public boolean deletePreference(String ownerId, String key) {
        return preferences.delete(owner(ownerId), key);
    }
    public int forget(String ownerId) {
        return preferences.deleteAll(owner(ownerId));
    }

    private String owner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        return ownerId.trim();
    }
}
