package com.minikun.personality.learning;

import com.minikun.personality.model.AdaptationSignal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryAdaptationSignalStore implements AdaptationSignalStore {
    private final Map<String, AdaptationSignal> values = new HashMap<>();

    @Override
    public synchronized void record(String ownerId, String dimension, String value, double delta,
            boolean explicit, double competingDecay, Instant observedAt) {
        values.replaceAll((key, signal) -> signal.ownerId().equals(ownerId)
                && signal.dimension().equals(dimension) && !signal.value().equals(value)
                ? new AdaptationSignal(signal.ownerId(), signal.dimension(), signal.value(),
                        signal.observations(), signal.explicitObservations(),
                        signal.score() * competingDecay, observedAt)
                : signal);
        String key = ownerId + "\u0000" + dimension + "\u0000" + value;
        AdaptationSignal current = values.get(key);
        values.put(key, new AdaptationSignal(ownerId, dimension, value,
                current == null ? 1 : current.observations() + 1,
                (current == null ? 0 : current.explicitObservations()) + (explicit ? 1 : 0),
                clamp((current == null ? 0 : current.score()) + delta), observedAt));
    }

    @Override
    public synchronized List<AdaptationSignal> findByOwner(String ownerId) {
        return values.values().stream().filter(signal -> signal.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(AdaptationSignal::dimension)
                        .thenComparing(AdaptationSignal::value))
                .toList();
    }

    @Override
    public synchronized int deleteAll(String ownerId) {
        List<String> keys = new ArrayList<>();
        values.forEach((key, signal) -> { if (signal.ownerId().equals(ownerId)) keys.add(key); });
        keys.forEach(values::remove);
        return keys.size();
    }

    private double clamp(double value) {
        return Math.max(-10, Math.min(10, value));
    }
}
