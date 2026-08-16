package com.minikun.personality.preference;

import com.minikun.personality.model.Preference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryPreferenceStore implements PreferenceStore {
    private final List<Preference> values = new CopyOnWriteArrayList<>();
    public List<Preference> findByOwner(String ownerId) { return values.stream().filter(p -> p.ownerId().equals(ownerId)).toList(); }
    public void save(Preference preference) { values.removeIf(p -> p.ownerId().equals(preference.ownerId()) && p.key().equals(preference.key())); values.add(preference); }
    public boolean delete(String ownerId, String key) {
        return values.removeIf(p -> p.ownerId().equals(ownerId) && p.key().equals(key));
    }
    public int deleteAll(String ownerId) {
        int before = values.size();
        values.removeIf(p -> p.ownerId().equals(ownerId));
        return before - values.size();
    }
}
