package com.minikun.personality.profile;

import com.minikun.personality.model.UserProfile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryUserProfileStore implements UserProfileStore {
    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    public UserProfile find(String ownerId) {
        return profiles.getOrDefault(ownerId, new UserProfile(ownerId, "", "", "", ""));
    }
    public void save(UserProfile profile) { profiles.put(profile.ownerId(), profile); }
}
