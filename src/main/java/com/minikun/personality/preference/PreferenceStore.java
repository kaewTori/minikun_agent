package com.minikun.personality.preference;

import com.minikun.personality.model.Preference;
import java.util.List;

public interface PreferenceStore {
    List<Preference> findByOwner(String ownerId);
    void save(Preference preference);
    boolean delete(String ownerId, String key);
    int deleteAll(String ownerId);
}
