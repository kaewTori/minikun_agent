package com.minikun.personality.learning;

import com.minikun.personality.model.AdaptationSignal;
import com.minikun.personality.model.Preference;
import com.minikun.personality.preference.PreferenceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;

/** Learns bounded response defaults without retaining raw user messages. */
public final class AdaptivePreferenceLearningService {
    private final AdaptationSignalStore signals;
    private final PreferenceStore preferences;
    private final ResponsePreferenceDetector detector;
    private final Clock clock;
    private final int minimumObservations;
    private final double minimumScore;
    private final boolean enabled;
    private final Duration evidenceMaxAge;

    public AdaptivePreferenceLearningService(
            AdaptationSignalStore signals,
            PreferenceStore preferences,
            ResponsePreferenceDetector detector,
            Clock clock,
            @Value("${minikun.adaptation.enabled:true}") boolean enabled,
            @Value("${minikun.adaptation.minimum-observations:3}") int minimumObservations,
            @Value("${minikun.adaptation.minimum-score:0.6}") double minimumScore,
            @Value("${minikun.adaptation.evidence-max-age:365d}") Duration evidenceMaxAge) {
        this.signals = Objects.requireNonNull(signals);
        this.preferences = Objects.requireNonNull(preferences);
        this.detector = Objects.requireNonNull(detector);
        this.clock = Objects.requireNonNull(clock);
        this.enabled = enabled;
        this.evidenceMaxAge = Objects.requireNonNull(evidenceMaxAge);
        if (minimumObservations < 1) throw new IllegalArgumentException("minimum observations must be positive");
        if (!Double.isFinite(minimumScore) || minimumScore <= 0) {
            throw new IllegalArgumentException("minimum score must be positive");
        }
        if (evidenceMaxAge.isZero() || evidenceMaxAge.isNegative()) {
            throw new IllegalArgumentException("evidence max age must be positive");
        }
        this.minimumObservations = minimumObservations;
        this.minimumScore = minimumScore;
    }

    public void observe(String ownerId, String message) {
        if (!enabled) return;
        String owner = owner(ownerId);
        Instant now = clock.instant();
        List<AdaptationObservation> observations = detector.detect(message);
        for (AdaptationObservation observation : observations) {
            signals.record(owner, observation.dimension(), observation.value(), observation.weight(),
                    observation.explicit(), observation.explicit() ? .35 : .95, now);
        }
        observations.stream().map(AdaptationObservation::dimension).distinct()
                .forEach(dimension -> reconcile(owner, dimension, now));
    }

    public AdaptationSnapshot feedback(String ownerId, String dimension, String value, boolean positive) {
        if (!enabled) throw new IllegalStateException("adaptive companion is disabled");
        String owner = owner(ownerId);
        String normalizedDimension = normalize(dimension, "dimension");
        String normalizedValue = normalize(value, "value");
        if (!AdaptationDimensions.supported(normalizedDimension, normalizedValue)) {
            throw new IllegalArgumentException("unsupported adaptation dimension or value");
        }
        Instant now = clock.instant();
        signals.record(owner, normalizedDimension, normalizedValue, positive ? 2 : -2,
                positive, positive ? .25 : 1, now);
        reconcile(owner, normalizedDimension, now);
        return snapshot(owner);
    }

    public AdaptationSnapshot snapshot(String ownerId) {
        String owner = owner(ownerId);
        List<Preference> learned = preferences.findByOwner(owner).stream()
                .filter(preference -> preference.key().startsWith(AdaptationDimensions.PREFIX))
                .sorted(Comparator.comparing(Preference::key)).toList();
        return new AdaptationSnapshot(owner, enabled, learned, signals.findByOwner(owner), clock.instant());
    }

    public ResetResult reset(String ownerId) {
        String owner = owner(ownerId);
        int deletedSignals = signals.deleteAll(owner);
        int deletedPreferences = 0;
        for (String dimension : AdaptationDimensions.dimensions()) {
            if (preferences.delete(owner, AdaptationDimensions.preferenceKey(dimension))) deletedPreferences++;
        }
        return new ResetResult(owner, deletedSignals, deletedPreferences);
    }

    private void reconcile(String owner, String dimension, Instant now) {
        List<AdaptationSignal> candidates = signals.findByOwner(owner).stream()
                .filter(signal -> signal.dimension().equals(dimension))
                .filter(signal -> !signal.updatedAt().plus(evidenceMaxAge).isBefore(now))
                .sorted(Comparator.comparingDouble(AdaptationSignal::score).reversed()
                        .thenComparing(Comparator.comparingInt(AdaptationSignal::explicitObservations).reversed())
                        .thenComparing(AdaptationSignal::value))
                .toList();
        String key = AdaptationDimensions.preferenceKey(dimension);
        if (candidates.isEmpty()) {
            preferences.delete(owner, key);
            return;
        }
        AdaptationSignal winner = candidates.getFirst();
        double runnerUpScore = candidates.size() > 1 ? candidates.get(1).score() : Double.NEGATIVE_INFINITY;
        boolean explicit = winner.explicitObservations() > 0;
        boolean enoughEvidence = explicit
                ? winner.score() >= .75
                : winner.observations() >= minimumObservations && winner.score() >= minimumScore;
        boolean clearWinner = explicit || winner.score() - runnerUpScore >= .25;
        if (!enoughEvidence || !clearWinner) {
            preferences.delete(owner, key);
            return;
        }
        double confidence = explicit ? .95 : Math.min(.9, .5 + Math.min(4, winner.score()) * .1);
        preferences.save(new Preference(owner, key, winner.value(), confidence, now));
    }

    private String owner(String ownerId) {
        String owner = normalize(ownerId, "owner_id");
        if ("*".equals(owner)) throw new IllegalArgumentException("owner_id must not be wildcard");
        if (owner.length() > 255) throw new IllegalArgumentException("owner_id must not exceed 255 characters");
        return owner;
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ResetResult(String ownerId, int deletedSignals, int deletedPreferences) {}
}
