package com.minikun.personality.profile;

import com.minikun.personality.model.UserProfile;

public interface UserProfileStore { UserProfile find(String ownerId); void save(UserProfile profile); }
