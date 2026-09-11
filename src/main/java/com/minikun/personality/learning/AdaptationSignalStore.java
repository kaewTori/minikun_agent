package com.minikun.personality.learning;

import com.minikun.personality.model.AdaptationSignal;
import java.time.Instant;
import java.util.List;

public interface AdaptationSignalStore {
    void record(String ownerId, String dimension, String value, double delta,
            boolean explicit, double competingDecay, Instant observedAt);
    List<AdaptationSignal> findByOwner(String ownerId);
    int deleteAll(String ownerId);
    void deleteDimension(String ownerId, String dimension);
}
