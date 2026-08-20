package com.minikun.voice;

import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Reports voice capability readiness without making the whole agent unavailable during optional runtime repair. */
public final class VoiceHealthIndicator implements HealthIndicator {
    private final VoiceService voice;

    public VoiceHealthIndicator(VoiceService voice) {
        this.voice = Objects.requireNonNull(voice, "voice service must not be null");
    }

    @Override
    public Health health() {
        VoiceStatus status = voice.status();
        return Health.up()
                .withDetail("enabled", status.enabled())
                .withDetail("transcription", status.transcriptionAvailable() ? "READY" : "UNAVAILABLE")
                .withDetail("synthesis", status.synthesisAvailable() ? "READY" : "UNAVAILABLE")
                .withDetail("localOnly", status.localOnly())
                .withDetail("storesAudio", status.storesAudio())
                .build();
    }
}
